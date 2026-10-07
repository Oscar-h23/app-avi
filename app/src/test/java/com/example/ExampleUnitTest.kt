package com.example

import com.example.api.SigoApiService
import com.example.model.EstadoSincronizacion
import com.example.model.Incident
import com.example.model.LoginRequest
import com.example.model.LoginResponse
import com.example.parser.AviParser
import com.example.util.AviDateUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ExampleUnitTest {

    @Test
    fun testSerializacionLoginRequestMoshi() {
        // Verifica que LoginRequest(codigo = 2396) se convierte exactamente a {"codigo":2396}
        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(LoginRequest::class.java)

        val request = LoginRequest(codigo = 2396)
        val json = adapter.toJson(request)

        assertEquals("{\"codigo\":2396}", json)
    }

    @Test
    fun testDeserializacionLoginResponse() {
        val jsonResponse = """
            {
              "token": "JWT_GENERADO_TEST_XYZ",
              "tipo": "Bearer",
              "expiresIn": 28800,
              "usuario": {
                "id": 10,
                "codigo": 2396,
                "nombre": "Carlos Perez",
                "rol": "OPERADOR",
                "plazaId": 3,
                "plaza": "P4"
              }
            }
        """.trimIndent()

        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(LoginResponse::class.java)

        val response = adapter.fromJson(jsonResponse)
        assertNotNull(response)
        assertEquals("JWT_GENERADO_TEST_XYZ", response?.token)
        assertEquals("Bearer", response?.tipo)
        assertEquals(28800L, response?.expiresIn)
        assertEquals(10L, response?.usuario?.id)
        assertEquals(2396, response?.usuario?.codigo)
        assertEquals("Carlos Perez", response?.usuario?.nombre)
        assertEquals("OPERADOR", response?.usuario?.rol)
        assertEquals(3L, response?.usuario?.plazaId)
        assertEquals("P4", response?.usuario?.plaza)
    }

    @Test
    fun testRetrofitInstanciaSigoApiServiceSinErrorDeConverter() {
        // Verifica que Retrofit pueda crear la implementación del servicio sin lanzar
        // "Unable to create @Body converter for class com.example.model.LoginRequest"
        val apiService = SigoApiService.create("http://192.168.1.53:8080/")
        assertNotNull(apiService)
    }

    @Test
    fun testMockWebServerLoginEndpoint() = runBlocking {
        val server = MockWebServer()
        server.start()

        try {
            val mockResponseBody = """
                {
                  "token": "JWT_MOCK_SUCCESS",
                  "tipo": "Bearer",
                  "expiresIn": 28800,
                  "usuario": {
                    "id": 10,
                    "codigo": 2396,
                    "nombre": "Operador Peaje",
                    "rol": "OPERADOR",
                    "plazaId": 3,
                    "plaza": "P4"
                  }
                }
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(mockResponseBody)
            )

            val apiService = SigoApiService.create(server.url("/").toString())

            val response = apiService.login(LoginRequest(codigo = 2396))

            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals("JWT_MOCK_SUCCESS", body?.token)
            assertEquals(2396, body?.usuario?.codigo)
            assertEquals("P4", body?.usuario?.plaza)

            // Verificar la petición que recibió el servidor
            val recordedRequest = server.takeRequest()
            assertEquals("POST", recordedRequest.method)
            assertEquals("/api/avi/auth/login", recordedRequest.path)
            assertTrue(recordedRequest.headers["Content-Type"]?.contains("application/json") == true)
            assertEquals("{\"codigo\":2396}", recordedRequest.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testFugaViaCientoUnoPlacaBravoTangoLima() {
        // "Fuga vía ciento uno placa Bravo Tango Lima dos cuatro cinco"
        val result = AviParser.parse("Fuga vía ciento uno placa Bravo Tango Lima dos cuatro cinco")
        assertEquals("BTL245", result.placa)
        assertEquals(101, result.via)
        assertEquals("FUGA", result.accion)
        assertTrue(result.valido)
        assertTrue(result.errores.isEmpty())
    }

    @Test
    fun testDerivadoViaCientoDosPlacaAlfaBravoCharlie() {
        // "Derivado vía ciento dos placa Alfa Bravo Charlie uno dos tres"
        val result = AviParser.parse("Derivado vía ciento dos placa Alfa Bravo Charlie uno dos tres")
        assertEquals("ABC123", result.placa)
        assertEquals(102, result.via)
        assertEquals("DERIVADO", result.accion)
        assertTrue(result.valido)
    }

    @Test
    fun testOrdenInvertidoPlacaFugaVia() {
        // "Bravo Tango Lima dos cuatro cinco fuga vía ciento uno"
        val result = AviParser.parse("Bravo Tango Lima dos cuatro cinco fuga vía ciento uno")
        assertEquals("BTL245", result.placa)
        assertEquals(101, result.via)
        assertEquals("FUGA", result.accion)
        assertTrue(result.valido)
    }

    @Test
    fun testViaConDigitosIndividualesUnoCincoUno() {
        // "Placa alfa bravo charlie uno dos tres, vía uno cinco uno, fuga"
        val result = AviParser.parse("Placa alfa bravo charlie uno dos tres, vía uno cinco uno, fuga")
        assertEquals("ABC123", result.placa)
        assertEquals(151, result.via)
        assertEquals("FUGA", result.accion)
        assertTrue(result.valido)
    }

    @Test
    fun testPlacaDirectaAlfanumericaBTL245() {
        val result = AviParser.parse("Placa BTL245, vía ciento cincuenta y uno, fuga")
        assertEquals("BTL245", result.placa)
        assertEquals(151, result.via)
        assertEquals("FUGA", result.accion)
        assertTrue(result.valido)
    }

    @Test
    fun testDictadoIncompletoSinPlacaNoInventaDatos() {
        val result = AviParser.parse("Vía uno cinco uno, fuga")
        assertEquals("", result.placa)
        assertEquals(151, result.via)
        assertEquals("FUGA", result.accion)
        assertFalse(result.valido)
        assertTrue(result.errores.any { it.contains("placa", ignoreCase = true) })
    }

    @Test
    fun testDictadoIncompletoSinViaNoInventaDatos() {
        val result = AviParser.parse("Placa alfa bravo charlie uno dos tres, fuga")
        assertEquals("ABC123", result.placa)
        assertEquals(null, result.via)
        assertEquals("FUGA", result.accion)
        assertFalse(result.valido)
        assertTrue(result.errores.any { it.contains("vía", ignoreCase = true) || it.contains("via", ignoreCase = true) })
    }

    @Test
    fun testViaMilDoscientosTreintaYCuatro() {
        val result = AviParser.parse("Placa ABC123 vía mil doscientos treinta y cuatro fuga")
        assertEquals("ABC123", result.placa)
        assertEquals(1234, result.via)
        assertEquals("FUGA", result.accion)
        assertTrue(result.valido)
    }

    @Test
    fun testGeneracionUuidUnicoPorEvento() {
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()
        assertNotEquals(id1, id2)
        assertTrue(id1.length > 20)
    }

    @Test
    fun testPreservacionHoraEventoIso8601() {
        val iso = AviDateUtils.nowLimaIso()
        // Debe contener la zona horaria de Perú "-05:00"
        assertTrue("Debe contener zona horaria -05:00", iso.contains("-05:00"))
        val display = AviDateUtils.formatIsoToDisplay(iso)
        assertTrue("Debe formatear a formato legible dd/MM/yyyy", display.contains("/"))
    }

    @Test
    fun testModeloIncidenciaEstadoInicialPendiente() {
        val incidente = Incident(
            placa = "BTL245",
            via = 101,
            accion = "FUGA",
            plazaId = "P4",
            fechaHoraEvento = AviDateUtils.nowLimaIso(),
            textoReconocido = "Fuga vía ciento uno placa Bravo Tango Lima dos cuatro cinco"
        )
        assertEquals(EstadoSincronizacion.PENDIENTE, incidente.estadoSincronizacion)
        assertEquals(0, incidente.intentosSincronizacion)
        assertEquals("BTL245", incidente.placa)
    }
}
