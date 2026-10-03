#!/usr/bin/env bash
# Local DEV deploy tests. All external commands that could affect a server are mocked.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
old_sha="$(printf 'a%.0s' {1..40})"
new_sha="$(printf 'b%.0s' {1..40})"
branch_sha="$(printf 'c%.0s' {1..40})"
mkdir -p "${work}/bin"

for sha in "${old_sha}" "${new_sha}" "${branch_sha}"; do
  mkdir -p "${work}/fixtures/${sha}/scripts"
  printf 'compose from %s\n' "${sha}" > "${work}/fixtures/${sha}/compose.dev.yaml"
  for script in cd-deploy.sh cd-poll.sh backup-db.sh monitor-health-dev.sh; do
    printf 'script %s from %s\n' "${script}" "${sha}" > "${work}/fixtures/${sha}/scripts/${script}"
  done
  printf 'unrelated\n' > "${work}/fixtures/${sha}/scripts/deploy.sh"
done

cat > "${work}/bin/git" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  ls-remote) printf '%s\trefs/heads/develop\n' "$MOCK_NEW_SHA" ;;
  fetch)
    [[ "$2" == origin ]] || exit 90
    if [[ "$3" == develop ]]; then
      if [[ "${MOCK_ADVANCE:-0}" == 1 ]]; then
        printf '%s' "$MOCK_BRANCH_SHA" > "${MOCK_PROJECT}/branch-tip"
      fi
    elif [[ "$3" == "$MOCK_NEW_SHA" ]]; then
      touch "${MOCK_PROJECT}/target-fetched"
    else
      exit 90
    fi ;;
  cat-file)
    [[ "$2" == -t ]] || exit 90
    if [[ "$3" == "$MOCK_NEW_SHA" && "${MOCK_REQUIRE_EXACT_FETCH:-0}" == 1 \
       && ! -e "${MOCK_PROJECT}/target-fetched" ]]; then
      exit 1
    fi
    if [[ "$3" == "$MOCK_OLD_SHA" && -e "${MOCK_PROJECT}/missing-old-commit" ]]; then
      exit 1
    fi
    case "$3" in
      "$MOCK_OLD_SHA"|"$MOCK_NEW_SHA"|"$MOCK_BRANCH_SHA") printf 'commit\n' ;;
      *) exit 1 ;;
    esac ;;
  archive)
    [[ "$2" == --format=tar ]] || exit 90
    sha="$3"
    if [[ "$sha" == origin/develop ]]; then
      sha="$(cat "${MOCK_PROJECT}/branch-tip")"
    fi
    [[ -d "${MOCK_FIXTURES}/${sha}" ]] || exit 1
    shift 3
    [[ "${1:-}" == -- ]] && shift
    tar -C "${MOCK_FIXTURES}/${sha}" -cf - "$@" ;;
  *) exit 90 ;;
esac
MOCK
cat > "${work}/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$1 $2" in
  'manifest inspect') exit 0 ;;
  'image inspect')
    [[ "$3" == "ghcr.io/marfern2/tareas-app-api:${MOCK_OLD_SHA}" ]]
    [[ ! -e "${MOCK_PROJECT}/missing-image" ]]
    exit ;;
esac
[[ "$1" == compose && "$2" == -f && "$3" == compose.dev.yaml && "$4" == --env-file && "$5" == "${MOCK_PROJECT}/.env" ]] || exit 90
shift 5
tag="$(sed -n 's/^API_IMAGE_TAG=//p' "${MOCK_PROJECT}/.env")"
case "$*" in
  'pull api')
    printf 'pull %s\n' "$tag" >> "${MOCK_PROJECT}/docker-calls"
    [[ ! -e "${MOCK_PROJECT}/pull-fails" ]] ;;
  'up -d --no-deps api')
    printf 'up %s\n' "$tag" >> "${MOCK_PROJECT}/docker-calls"
    if [[ "$tag" == "$MOCK_NEW_SHA" ]]; then
      # Simulate a container already replaced before Compose reports failure.
      printf 'new' > "${MOCK_PROJECT}/running"
      if [[ -e "${MOCK_PROJECT}/signal-after-up" ]]; then
        kill -TERM "$PPID"
      fi
      [[ ! -e "${MOCK_PROJECT}/new-up-fails" ]]
    elif [[ "$tag" == "$MOCK_OLD_SHA" ]]; then
      [[ ! -e "${MOCK_PROJECT}/old-up-fails" ]] || exit 1
      printf 'old' > "${MOCK_PROJECT}/running"
    else
      exit 90
    fi ;;
  *) exit 90 ;;
esac
MOCK
cat > "${work}/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
url="${*: -1}"
running="$(cat "${MOCK_PROJECT}/running")"
if [[ "$running" == new && "$url" == http:* && -e "${MOCK_PROJECT}/new-local-fails" ]] ||
   [[ "$running" == new && "$url" == https:* && -e "${MOCK_PROJECT}/new-public-fails" ]] ||
   [[ "$running" == old && -e "${MOCK_PROJECT}/old-health-fails" ]]; then
  printf '{"status":"DOWN"}'
else
  printf '{"status":"UP"}'
fi
MOCK
cat > "${work}/bin/sleep" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
chmod +x "${work}/bin/"*

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_file() { [[ "$(cat "$1")" == "$2" ]] || fail "$1 tiene valor incorrecto"; }
assert_missing() { [[ ! -e "$1" ]] || fail "$1 no debe existir"; }

prepare() {
  local name="$1"
  project="${work}/${name}"
  mkdir -p "${project}"
  printf 'POSTGRES_PASSWORD=secret-fixture\nAPI_IMAGE_TAG=%s\nJWT_SECRET=unchanged-fixture\n' "$old_sha" > "${project}/.env"
  cp "${project}/.env" "${project}/original-env"
  printf '%s' "$old_sha" > "${project}/.deployed-sha"
  printf 'old' > "${project}/running"
  printf '%s' "$new_sha" > "${project}/branch-tip"
  sed "s|PROJECT_DIR=\"/srv/docker/tareas-app-api-dev\"|PROJECT_DIR=\"${project}\"|" \
    "${root}/scripts/cd-deploy.sh" > "${project}/cd-deploy-under-test.sh"
}

run_deploy() {
  local expected="$1" target="${2:-${new_sha}}" actual
  actual=0
  env DEPLOY_ENV=dev MOCK_PROJECT="${project}" MOCK_OLD_SHA="${old_sha}" MOCK_NEW_SHA="${new_sha}" \
    MOCK_BRANCH_SHA="${branch_sha}" MOCK_FIXTURES="${work}/fixtures" MOCK_ADVANCE="${MOCK_ADVANCE:-0}" \
    PATH="${work}/bin:${PATH}" bash "${project}/cd-deploy-under-test.sh" "$target" \
    > "${project}/output" 2>&1 || actual=$?
  [[ "$actual" == "$expected" ]] || { cat "${project}/output" >&2; fail "exit ${actual}, esperado ${expected}"; }
  cmp -s "${project}/original-env" "${project}/.env" || fail ".env o secretos cambiaron"
  # The mock rejects every Compose action outside pull/up for api.
  [[ ! -e "${project}/unexpected-docker-call" ]] || fail "accion Docker inesperada"
}

prepare compose_ok
touch "${project}/new-up-fails"
run_deploy 3
assert_file "${project}/running" old
assert_file "${project}/.failed-sha" "$new_sha"
assert_file "${project}/.deployed-sha" "$old_sha"
[[ "$(cat "${project}/docker-calls")" == "$(printf 'pull %s\nup %s\nup %s' "$new_sha" "$new_sha" "$old_sha")" ]] || fail "rollback no recreo solo api con SHA anterior"
printf 'PASS: compose up falla despues de reemplazar contenedor; rollback sano, marcadores y exit 3\n'

prepare compose_rollback_fails
touch "${project}/new-up-fails" "${project}/old-up-fails"
run_deploy 4
assert_file "${project}/running" new
assert_file "${project}/.failed-sha" "$new_sha"
assert_missing "${project}/.deployed-sha"
printf 'PASS: compose up y rollback fallan; SHA nuevo fallido y exit 4\n'

prepare bootstrap
touch "${project}/new-up-fails"
rm "${project}/.deployed-sha"
run_deploy 4
assert_file "${project}/.failed-sha" "$new_sha"
assert_missing "${project}/.deployed-sha"
[[ "$(cat "${project}/docker-calls")" == "$(printf 'pull %s\nup %s' "$new_sha" "$new_sha")" ]] || fail "bootstrap intento imagen anterior no verificada"
printf 'PASS: bootstrap sin SHA anterior no inventa rollback\n'

prepare missing_image
touch "${project}/new-up-fails" "${project}/missing-image"
run_deploy 4
assert_file "${project}/.failed-sha" "$new_sha"
assert_missing "${project}/.deployed-sha"
printf 'PASS: imagen anterior inexistente produce exit 4\n'

prepare mismatched_sha
touch "${project}/new-up-fails"
printf '%s' "$(printf 'c%.0s' {1..40})" > "${project}/.deployed-sha"
run_deploy 4
assert_file "${project}/.failed-sha" "$new_sha"
assert_missing "${project}/.deployed-sha"
printf 'PASS: SHA anterior inconsistente con .env no se usa\n'

prepare local_health
touch "${project}/new-local-fails"
run_deploy 3
assert_file "${project}/running" old
assert_file "${project}/.failed-sha" "$new_sha"
assert_file "${project}/.deployed-sha" "$old_sha"
printf 'PASS: timeout health local reutiliza rollback\n'

prepare public_health
touch "${project}/new-public-fails"
run_deploy 3
assert_file "${project}/running" old
assert_file "${project}/.failed-sha" "$new_sha"
assert_file "${project}/.deployed-sha" "$old_sha"
printf 'PASS: timeout health publico reutiliza rollback\n'

prepare rollback_unhealthy
touch "${project}/new-up-fails" "${project}/old-health-fails"
run_deploy 4
assert_file "${project}/.failed-sha" "$new_sha"
assert_missing "${project}/.deployed-sha"
printf 'PASS: rollback recreado pero sin health produce exit 4\n'

prepare pull_failure
touch "${project}/pull-fails"
run_deploy 1
assert_file "${project}/running" old
assert_file "${project}/.deployed-sha" "$old_sha"
assert_file "${project}/compose.dev.yaml" "compose from ${old_sha}"
assert_missing "${project}/.failed-sha"
[[ "$(cat "${project}/docker-calls")" == "pull ${new_sha}" ]] || fail "pull fallo y se intento up"
printf 'PASS: pull fallido conserva despliegue y permite reintento\n'

prepare interrupted
touch "${project}/signal-after-up"
run_deploy 3
assert_file "${project}/running" old
assert_file "${project}/.failed-sha" "$new_sha"
assert_file "${project}/.deployed-sha" "$old_sha"
printf 'PASS: interrupcion tras reemplazo parcial ejecuta rollback\n'

prepare success
actual=0
env DEPLOY_ENV=dev MOCK_PROJECT="${project}" MOCK_OLD_SHA="${old_sha}" MOCK_NEW_SHA="${new_sha}" \
  MOCK_BRANCH_SHA="${branch_sha}" MOCK_FIXTURES="${work}/fixtures" \
  PATH="${work}/bin:${PATH}" bash "${project}/cd-deploy-under-test.sh" "$new_sha" \
  > "${project}/output" 2>&1 || actual=$?
[[ "$actual" == 0 ]] || { cat "${project}/output" >&2; fail "deploy sano exit ${actual}"; }
assert_file "${project}/.deployed-sha" "$new_sha"
assert_missing "${project}/.failed-sha"
assert_file "${project}/running" new
printf 'PASS: deploy sano escribe SHA nuevo solo tras health\n'

prepare branch_race
mkdir -p "${project}/scripts"
cp "${project}/cd-deploy-under-test.sh" "${project}/scripts/cd-deploy.sh"
chmod +x "${project}/scripts/cd-deploy.sh"
sed "s|PROJECT_DIR=\"/srv/docker/tareas-app-api-dev\"|PROJECT_DIR=\"${project}\"|" \
  "${root}/scripts/cd-poll.sh" > "${project}/scripts/cd-poll.sh"
actual=0
(
  cd "${project}"
  env DEPLOY_ENV=dev MOCK_PROJECT="${project}" MOCK_OLD_SHA="${old_sha}" MOCK_NEW_SHA="${new_sha}" \
    MOCK_BRANCH_SHA="${branch_sha}" MOCK_FIXTURES="${work}/fixtures" MOCK_ADVANCE=1 \
    MOCK_REQUIRE_EXACT_FETCH=1 \
    PATH="${work}/bin:${PATH}" bash ./scripts/cd-poll.sh
) > "${project}/output" 2>&1 || actual=$?
[[ "$actual" == 0 ]] || { cat "${project}/output" >&2; fail "poller/deploy exit ${actual}"; }
assert_file "${project}/branch-tip" "$branch_sha"
[[ -e "${project}/target-fetched" ]] || fail "SHA A no se obtuvo tras avance de rama"
assert_file "${project}/.deployed-sha" "$new_sha"
assert_file "${project}/compose.dev.yaml" "compose from ${new_sha}"
for script in cd-deploy.sh cd-poll.sh backup-db.sh monitor-health-dev.sh; do
  assert_file "${project}/scripts/${script}" "script ${script} from ${new_sha}"
done
assert_missing "${project}/scripts/deploy.sh"
[[ "$(cat "${project}/docker-calls")" == "$(printf 'pull %s\nup %s' "$new_sha" "$new_sha")" ]] || fail "imagen no corresponde al SHA del poller"
printf 'PASS: poller selecciona A, rama avanza a B y todos los artefactos e imagen siguen en A\n'

prepare rollback_artifacts
touch "${project}/new-up-fails"
MOCK_ADVANCE=1 run_deploy 3
assert_file "${project}/compose.dev.yaml" "compose from ${old_sha}"
for script in cd-deploy.sh cd-poll.sh backup-db.sh monitor-health-dev.sh; do
  assert_file "${project}/scripts/${script}" "script ${script} from ${old_sha}"
done
assert_file "${project}/.deployed-sha" "$old_sha"
printf 'PASS: rollback restaura compose y scripts del SHA anterior exacto\n'

prepare rollback_missing_commit
touch "${project}/new-up-fails" "${project}/missing-old-commit"
run_deploy 4
assert_missing "${project}/.deployed-sha"
assert_file "${project}/.failed-sha" "$new_sha"
printf 'PASS: rollback sin commit anterior verificable invalida marcador y devuelve exit 4\n'

prepare invalid_sha
run_deploy 2 'origin/develop'
assert_missing "${project}/compose.dev.yaml"
run_deploy 2 "$(printf 'd%.0s' {1..40})"
assert_missing "${project}/compose.dev.yaml"
assert_file "${project}/.deployed-sha" "$old_sha"
printf 'PASS: ref arbitraria y commit inexistente se rechazan antes de sincronizar\n'
