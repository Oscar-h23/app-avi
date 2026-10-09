package com.example.domain

import com.example.model.EstadoSincronizacion
import com.example.model.Incident
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFiltersTest {

    private val incidents =
        listOf(
            Incident(
                id = "1",
                placa = "X1Z505",
                via = 101,
                accion = "FUGA",
                fechaHoraEvento =
                    "2026-10-08T20:00:00-05:00",
                estadoSincronizacion =
                    EstadoSincronizacion.SINCRONIZADO
            ),
            Incident(
                id = "2",
                placa = "Q0O090",
                via = 102,
                accion = "DERIVADO",
                fechaHoraEvento =
                    "2026-10-09T08:00:00-05:00",
                estadoSincronizacion =
                    EstadoSincronizacion.PENDIENTE
            ),
            Incident(
                id = "3",
                placa = "ABC123",
                via = 103,
                accion = "FUGA",
                fechaHoraEvento =
                    "2026-10-10T09:00:00-05:00",
                estadoSincronizacion =
                    EstadoSincronizacion.REQUIERE_REVISION
            )
        )

    @Test
    fun `search plate and date range`() {
        val result =
            HistoryFilters.apply(
                incidents = incidents,
                status =
                    HistoryStatusFilter.TODOS,
                plateQuery = "q0o",
                fromDate = "2026-10-09",
                toDate = "2026-10-09"
            )

        assertEquals(
            listOf("2"),
            result.items.map { it.id }
        )
        assertEquals(
            null,
            result.validationMessage
        )
    }

    @Test
    fun `rejected filter is separate from pending`() {
        val pending =
            HistoryFilters.apply(
                incidents,
                HistoryStatusFilter.PENDIENTES
            )
        val rejected =
            HistoryFilters.apply(
                incidents,
                HistoryStatusFilter.RECHAZADOS
            )

        assertEquals(
            listOf("2"),
            pending.items.map { it.id }
        )
        assertEquals(
            listOf("3"),
            rejected.items.map { it.id }
        )
    }

    @Test
    fun `invalid date is reported instead of silently filtering`() {
        val result =
            HistoryFilters.apply(
                incidents = incidents,
                status =
                    HistoryStatusFilter.TODOS,
                fromDate = "2026-99-99"
            )

        assertTrue(result.items.isEmpty())
        assertNotNull(
            result.validationMessage
        )
    }
}
