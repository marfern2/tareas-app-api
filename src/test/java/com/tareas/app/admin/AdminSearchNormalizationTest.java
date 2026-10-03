package com.tareas.app.admin;

import com.tareas.app.admin.repository.AdminTareaRepository;
import com.tareas.app.admin.repository.AdminTipoTareaRepository;
import com.tareas.app.admin.repository.AdminUsuarioRepository;
import com.tareas.app.admin.service.AdminTaskService;
import com.tareas.app.admin.service.AdminTaskTypeService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSearchNormalizationTest {

    @Test
    void listadoTareasNormalizaBusquedaNulaParaPostgresql() {
        AdminTareaRepository tareas = mock(AdminTareaRepository.class);
        AdminUsuarioRepository usuarios = mock(AdminUsuarioRepository.class);
        AdminTipoTareaRepository tipos = mock(AdminTipoTareaRepository.class);
        when(tareas.findGlobalWithFilters(
                eq(""), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        new AdminTaskService(tareas, usuarios, tipos)
                .listarTareas(null, null, null, null, null, 0, 20, null);

        verify(tareas).findGlobalWithFilters(
                eq(""), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void listadoTiposNormalizaBusquedaNulaParaPostgresql() {
        AdminTipoTareaRepository tipos = mock(AdminTipoTareaRepository.class);
        when(tipos.findGlobalWithTaskCounts(eq(""), any(Pageable.class)))
                .thenReturn(Page.empty());

        new AdminTaskTypeService(tipos).listarTipos(null, 0, 20, null);

        verify(tipos).findGlobalWithTaskCounts(eq(""), any(Pageable.class));
    }
}
