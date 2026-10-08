package com.example

import com.example.api.SigoApiService
import com.example.model.EstadoSincronizacion
import com.example.model.Incident
import com.example.model.LoginRequest
import com.example.model.LoginResponse
import com.example.parser.AviParser
import com.example.parser.AviCommandStage
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
        // "Placa Bravo Tango Lima dos cuatro cinco fuga vía ciento uno"
        val result = AviParser.parse("Placa Bravo Tango Lima dos cuatro cinco fuga vía ciento uno")
        assertEquals("BTL245", result.placa)
        assertEquals(101, result.via)
        assertEquals("FUGA", result.accion)
        assertTrue(result.valido)
    }

    @Test
    fun testNoInterpretaPlacaSinPalabraPlaca() {
        // Aunque haya letras y números después de la vía, no deben convertirse en placa
        // si el usuario todavía no dijo explícitamente "placa".
        val result = AviParser.parse("Fuga vía ciento uno Bravo Tango Lima dos cuatro cinco")
        assertEquals("", result.placa)
        assertEquals(101, result.via)
        assertEquals("FUGA", result.accion)
        assertFalse(result.valido)
        assertTrue(result.errores.any { it.contains("placa", ignoreCase = true) })
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
    fun testCodigoQEnViaYPlaca() {
        val result = AviParser.parse(
            "Fuga vía primero negativo primero placa Bravo Tango Lima segundo cuarto quinto"
        )

        assertEquals(101, result.via)
        assertEquals("BTL245", result.placa)
        assertEquals("FUGA", result.accion)
        assertTrue(result.valido)
    }

    @Test
    fun testCodigoQAceptaVariantesGramaticales() {
        val result = AviParser.parse(
            "Derivado vía primera negativo primera placa Alfa Bravo Charlie segunda cuarta quinta"
        )

        assertEquals(101, result.via)
        assertEquals("ABC245", result.placa)
        assertEquals("DERIVADO", result.accion)
        assertTrue(result.valido)
    }

    @Test
    fun testCodigoQNegativoRepresentaCeroEnPlaca() {
        val result = AviParser.parse(
            "Fuga vía primero negativo segundo placa Alfa Bravo Charlie negativo primero noveno"
        )

        assertEquals(102, result.via)
        assertEquals("ABC019", result.placa)
        assertTrue(result.valido)
    }

    @Test
    fun testCodigoQMezcladoConDigitosEnVia() {
        val result = AviParser.parse(
            "Fuga vía 1 negativo 1 placa Bravo Tango Lima segundo cuarto quinto"
        )

        assertEquals(101, result.via)
        assertEquals("BTL245", result.placa)
        assertTrue(result.valido)
    }

    @Test
    fun testOrdenOperacionViaPlacaRecibeMayorPuntaje() {
        val ordenCorrecto = AviParser.scoreCandidate(
            "Fuga vía primero negativo primero placa Bravo Tango Lima segundo cuarto quinto"
        )
        val ordenAlterado = AviParser.scoreCandidate(
            "Placa Bravo Tango Lima segundo cuarto quinto fuga vía primero negativo primero"
        )

        assertTrue(ordenCorrecto > ordenAlterado)
    }

    @Test
    fun testSeleccionaMejorHipotesisAunqueNoSeaLaPrimera() {
        val candidates = listOf(
            "Fuga vía primero negativo primero Bravo Tango Lima segundo cuarto quinto",
            "Fuga vía primero negativo primero placa Bravo Tango Lima segundo cuarto quinto",
            "Fuga vía primero negativo primero placa Bravo Tango"
        )

        val selected = AviParser.selectBestHypothesis(
            candidates = candidates,
            confidenceScores = floatArrayOf(0.92f, 0.80f, 0.95f)
        )

        assertEquals(
            "Fuga vía primero negativo primero placa Bravo Tango Lima segundo cuarto quinto",
            selected
        )
    }

    @Test
    fun testHipotesisConEstructuraCompletaTieneMayorPuntaje() {
        val incompleta = AviParser.scoreCandidate(
            "Fuga vía primero negativo primero Bravo Tango Lima segundo cuarto quinto"
        )
        val completa = AviParser.scoreCandidate(
            "Fuga vía primero negativo primero placa Bravo Tango Lima segundo cuarto quinto"
        )

        assertTrue(completa > incompleta)
    }

    @Test
    fun testPlacaPeruLetraNumeroLetraConCodigoQ() {
        val result = AviParser.parse(
            "Fuga vía primero negativo primero placa Alfa primero Bravo segundo tercero cuarto"
        )

        assertEquals(101, result.via)
        assertEquals("A1B234", result.placa)
        assertEquals("FUGA", result.accion)
        assertTrue(result.valido)
    }

    @Test
    fun testPlacaPeruTresLetrasSigueSiendoValida() {
        val result = AviParser.parse(
            "Fuga vía primero negativo primero placa Bravo Tango Lima segundo cuarto quinto"
        )

        assertEquals("BTL245", result.placa)
        assertTrue(result.valido)
    }

    @Test
    fun testPlacaPeruConSegundoYTercerCaracterNumericos() {
        val result = AviParser.parse(
            "Derivado vía primero negativo segundo placa Alfa primero segundo tercero cuarto quinto"
        )

        assertEquals(102, result.via)
        assertEquals("A12345", result.placa)
        assertEquals("DERIVADO", result.accion)
        assertTrue(result.valido)
    }

    @Test
    fun testPlacaPeruCompactaA1B234() {
        val result = AviParser.parse(
            "Fuga vía 101 placa A1B234"
        )

        assertEquals("A1B234", result.placa)
        assertEquals(101, result.via)
        assertTrue(result.valido)
    }

    @Test
    fun testPlacaInvalidaSiPrimeraPosicionEsNumero() {
        val result = AviParser.parse(
            "Fuga vía primero negativo primero placa primero Alfa Bravo segundo tercero cuarto"
        )

        assertEquals("1AB234", result.placa)
        assertFalse(result.valido)
        assertTrue(result.errores.any { it.contains("formato esperado", ignoreCase = true) })
    }

    @Test
    fun testMaquinaEstadosPideAccionPrimero() {
        val analysis = AviParser.analyzeCommand(
            "vía primero negativo primero placa Bravo Tango Lima segundo cuarto quinto"
        )

        assertEquals(AviCommandStage.ACCION, analysis.stage)
        assertFalse(analysis.parsed.valido)
    }

    @Test
    fun testMaquinaEstadosPideViaSiFaltaVia() {
        val analysis = AviParser.analyzeCommand(
            "Fuga placa Bravo Tango Lima segundo cuarto quinto"
        )

        assertEquals(AviCommandStage.VIA, analysis.stage)
        assertFalse(analysis.parsed.valido)
    }

    @Test
    fun testMaquinaEstadosPidePlacaSiFaltaPlaca() {
        val analysis = AviParser.analyzeCommand(
            "Fuga vía primero negativo primero"
        )

        assertEquals(AviCommandStage.PLACA, analysis.stage)
        assertFalse(analysis.parsed.valido)
    }

    @Test
    fun testMaquinaEstadosCompletaComandoValido() {
        val analysis = AviParser.analyzeCommand(
            "Fuga vía primero negativo primero placa Alfa primero Bravo segundo tercero cuarto"
        )

        assertEquals(AviCommandStage.COMPLETO, analysis.stage)
        assertTrue(analysis.parsed.valido)
        assertEquals("A1B234", analysis.parsed.placa)
        assertEquals(101, analysis.parsed.via)
    }

    @Test
    fun testValidaCadaPosicionDePlacaPeruana() {
        assertEquals(
            listOf(true, true, true, true, true, true),
            AviParser.platePositionValidity("A1B234")
        )

        assertEquals(
            listOf(false, true, true, true, true, true),
            AviParser.platePositionValidity("1AB234")
        )

        assertEquals(
            listOf(true, true, true, false, true, true),
            AviParser.platePositionValidity("A1BA34")
        )
    }

    @Test
    fun testViaFueraDeCatalogoRealSeRechaza() {
        val result = AviParser.parse(
            "Fuga vía primero negativo noveno placa Bravo Tango Lima segundo cuarto quinto",
            allowedVias = setOf(101, 102, 103)
        )

        assertEquals(109, result.via)
        assertFalse(result.valido)
        assertTrue(result.errores.any { it.contains("habilitada", ignoreCase = true) })
    }

    @Test
    fun testRankingPrefiereViaPermitida() {
        val selected = AviParser.selectBestHypothesis(
            candidates = listOf(
                "Fuga vía primero negativo noveno placa Bravo Tango Lima segundo cuarto quinto",
                "Fuga vía primero negativo primero placa Bravo Tango Lima segundo cuarto quinto"
            ),
            confidenceScores = floatArrayOf(0.95f, 0.75f),
            allowedVias = setOf(101, 102, 103)
        )

        assertEquals(
            "Fuga vía primero negativo primero placa Bravo Tango Lima segundo cuarto quinto",
            selected
        )
    }

    @Test
    fun testCorreccionParcialSoloPlacaConservaAccionYVia() {
        val previous = AviParser.parse(
            "Fuga vía primero negativo primero placa Bravo Tango"
        )

        val corrected = AviParser.mergeCorrection(
            previous = previous,
            rawCorrection = "placa Alfa primero Bravo segundo tercero cuarto"
        )

        assertEquals("FUGA", corrected.accion)
        assertEquals(101, corrected.via)
        assertEquals("A1B234", corrected.placa)
        assertTrue(corrected.valido)
    }

    @Test
    fun testCorreccionParcialSoloViaConservaPlaca() {
        val previous = AviParser.parse(
            "Derivado vía primero negativo noveno placa Bravo Tango Lima segundo cuarto quinto",
            allowedVias = setOf(101, 102)
        )

        val corrected = AviParser.mergeCorrection(
            previous = previous,
            rawCorrection = "vía primero negativo segundo",
            allowedVias = setOf(101, 102)
        )

        assertEquals("DERIVADO", corrected.accion)
        assertEquals(102, corrected.via)
        assertEquals("BTL245", corrected.placa)
        assertTrue(corrected.valido)
    }

    @Test
    fun testCorreccionDePlacaNoOcultaAccionQueFaltaba() {
        val previous = AviParser.parse(
            "vía primero negativo primero placa Bravo Tango"
        )

        val corrected = AviParser.mergeCorrection(
            previous = previous,
            rawCorrection = "placa Bravo Tango Lima segundo cuarto quinto"
        )

        assertFalse(corrected.valido)
        assertTrue(
            corrected.errores.any {
                it.contains("acción", ignoreCase = true) ||
                    it.contains("accion", ignoreCase = true)
            }
        )
    }

    @Test
    fun testNumeroViaSeConvierteACodigoQContextual() {
        assertEquals(
            "primero negativo primero",
            AviParser.numberToQPhrase(101)
        )
        assertEquals(
            "primero quinto primero",
            AviParser.numberToQPhrase(151)
        )
    }

    @Test
    fun testVocabularioDeReconocimientoIncluyeViasReales() {
        val phrases = AviParser.recognitionBiasingPhrases(
            allowedVias = setOf(101, 102)
        )

        assertTrue(phrases.contains("fuga"))
        assertTrue(phrases.contains("derivado"))
        assertTrue(phrases.contains("placa"))
        assertTrue(phrases.contains("alfa"))
        assertTrue(phrases.contains("negativo"))
        assertTrue(phrases.contains("vía 101"))
        assertTrue(phrases.contains("vía primero negativo primero"))
        assertTrue(phrases.contains("vía primero negativo segundo"))
    }

    @Test
    fun testVarianteFoneticaBraboSeInterpretaComoB() {
        val result = AviParser.parse(
            "Fuga vía primero negativo primero placa Alfa primero Brabo segundo tercero cuarto"
        )

        assertEquals("A1B234", result.placa)
        assertTrue(result.valido)
    }

    @Test
    fun testAliasFoneticoCompuestoFoxTrotFuncionaEnPlaca() {
        val result = AviParser.parse(
            "Fuga vía primero negativo primero placa Alfa Fox Trot Bravo segundo tercero cuarto"
        )

        assertEquals("AFB234", result.placa)
        assertTrue(result.valido)
    }

    @Test
    fun testVariantesYanquiYSuluSeInterpretanEnContextoDePlaca() {
        val result = AviParser.parse(
            "Fuga vía primero negativo primero placa Yanqui Sulu Bravo segundo tercero cuarto"
        )

        assertEquals("YZB234", result.placa)
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
