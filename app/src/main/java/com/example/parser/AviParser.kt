package com.example.parser

import com.example.model.ParsedCommand
import java.text.Normalizer
import java.util.Locale

enum class AviCommandStage {
    ACCION,
    VIA,
    PLACA,
    COMPLETO
}

data class AviCommandAnalysis(
    val stage: AviCommandStage,
    val parsed: ParsedCommand,
    val actionDetected: Boolean,
    val viaMarkerDetected: Boolean,
    val plateMarkerDetected: Boolean,
    val platePositions: List<Boolean>,
    val prompt: String
)

object AviParser {

    private val phoneticAlphabet = mapOf(
        "alfa" to "A", "alpha" to "A",
        "bravo" to "B", "brabo" to "B",
        "charlie" to "C", "charli" to "C", "charly" to "C", "chali" to "C",
        "delta" to "D", "dhelta" to "D",
        "echo" to "E", "eco" to "E", "hecho" to "E",
        "foxtrot" to "F", "fox trot" to "F", "fox" to "F",
        "golf" to "G", "gol" to "G",
        "hotel" to "H",
        "india" to "I",
        "juliet" to "J", "julieta" to "J", "yuliet" to "J",
        "kilo" to "K", "quilo" to "K",
        "lima" to "L",
        "mike" to "M", "maik" to "M", "mic" to "M",
        "november" to "N", "noviembre" to "N",
        "oscar" to "O",
        "papa" to "P",
        "quebec" to "Q", "quebek" to "Q", "quebeck" to "Q",
        "romeo" to "R",
        "sierra" to "S",
        "tango" to "T",
        "uniform" to "U", "uniforme" to "U",
        "victor" to "V",
        "whiskey" to "W", "whisky" to "W", "wisky" to "W", "wiski" to "W",
        "xray" to "X", "x ray" to "X", "exray" to "X", "equisray" to "X", "equis ray" to "X", "ray" to "X", "equis" to "X",
        "yankee" to "Y", "yanki" to "Y", "yanqui" to "Y",
        "zulu" to "Z", "sulu" to "Z",
        "a" to "A",
        "be" to "B", "ve grande" to "B", "be alta" to "B",
        "ce" to "C",
        "de" to "D",
        "e" to "E",
        "efe" to "F",
        "ge" to "G",
        "hache" to "H",
        "i" to "I", "i latina" to "I",
        "jota" to "J",
        "ka" to "K",
        "ele" to "L",
        "eme" to "M",
        "ene" to "N",
        "o" to "O",
        "pe" to "P",
        "cu" to "Q",
        "erre" to "R", "ere" to "R",
        "ese" to "S",
        "te" to "T",
        "u" to "U",
        "ve" to "V", "uve" to "V", "ve corta" to "V", "ve chica" to "V",
        "doble ve" to "W", "uve doble" to "W",
        "i griega" to "Y", "ye" to "Y",
        "zeta" to "Z", "ceta" to "Z"
    )

    // Código numérico operativo:
    // 0 NEGATIVO, 1 PRIMERO, 2 SEGUNDO, ... 9 NOVENO.
    private val singleDigits = mapOf(
        "negativo" to 0, "negativa" to 0,
        "primero" to 1, "primer" to 1, "primera" to 1,
        "segundo" to 2, "segunda" to 2,
        "tercero" to 3, "tercer" to 3, "tercera" to 3,
        "cuarto" to 4, "cuarta" to 4,
        "quinto" to 5, "quinta" to 5,
        "sexto" to 6, "sexta" to 6,
        "septimo" to 7, "septima" to 7,
        "octavo" to 8, "octava" to 8,
        "noveno" to 9, "novena" to 9,
        "cero" to 0,
        "uno" to 1, "un" to 1, "una" to 1,
        "dos" to 2,
        "tres" to 3,
        "cuatro" to 4,
        "cinco" to 5,
        "seis" to 6,
        "siete" to 7,
        "ocho" to 8,
        "nueve" to 9
    )

    private val spanishNumbers = mapOf(
        "cero" to 0, "uno" to 1, "un" to 1, "una" to 1, "dos" to 2, "tres" to 3,
        "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9,
        "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14,
        "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19,
        "veinte" to 20, "veintiuno" to 21, "veintidos" to 22, "veintitres" to 23,
        "veinticuatro" to 24, "veinticinco" to 25, "veintiseis" to 26, "veintisiete" to 27,
        "veintiocho" to 28, "veintinueve" to 29,
        "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60,
        "setenta" to 70, "ochenta" to 80, "noventa" to 90,
        "cien" to 100, "ciento" to 100, "doscientos" to 200, "trescientos" to 300,
        "cuatrocientos" to 400, "quinientos" to 500, "seiscientos" to 600,
        "setecientos" to 700, "ochocientos" to 800, "novecientos" to 900,
        "mil" to 1000
    )

    private val aliasFuga = listOf(
        "fuga", "fugado", "se dio a la fuga", "se fugo", "evasion"
    )
    private val aliasDerivado = listOf(
        "derivado", "derivar", "desvio", "desviado", "derivacion", "derivada"
    )

    private val viaKeywords = listOf("via ", "carril ", "pista ", "numero ")

    // Confusiones observables del ASR. No se reemplazan globalmente:
    // solo cuando la estructura del comando indica que AVIX está esperando ese marcador.
    private val viaContextAliases = setOf(
        "via", "habia", "avia", "bia", "dia", "guia"
    )

    private val placaContextAliases = setOf(
        "placa", "placas", "plata", "plaga", "flaca"
    )

    private val fugaContextAliases = setOf(
        "fuga", "juga"
    )

    private val canonicalPhoneticWords = listOf(
        "alfa", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel",
        "india", "juliet", "kilo", "lima", "mike", "november", "oscar", "papa",
        "quebec", "romeo", "sierra", "tango", "uniform", "victor", "whiskey",
        "x ray", "yankee", "zulu"
    )

    private val canonicalQDigits = listOf(
        "negativo", "primero", "segundo", "tercero", "cuarto",
        "quinto", "sexto", "septimo", "octavo", "noveno"
    )

    private val canonicalPhoneticMap = mapOf(
        "alfa" to "A", "bravo" to "B", "charlie" to "C", "delta" to "D",
        "echo" to "E", "foxtrot" to "F", "golf" to "G", "hotel" to "H",
        "india" to "I", "juliet" to "J", "kilo" to "K", "lima" to "L",
        "mike" to "M", "november" to "N", "oscar" to "O", "papa" to "P",
        "quebec" to "Q", "romeo" to "R", "sierra" to "S", "tango" to "T",
        "uniform" to "U", "victor" to "V", "whiskey" to "W",
        "yankee" to "Y", "zulu" to "Z"
    )

    fun numberToQPhrase(value: Int): String {
        if (value < 0) return ""
        return value.toString()
            .mapNotNull { ch ->
                ch.digitToIntOrNull()?.let { digit -> canonicalQDigits[digit] }
            }
            .joinToString(" ")
    }

    /**
     * Vocabulario contextual entregado al SpeechRecognizer cuando la versión
     * de Android soporta biasing strings. Incluye dominio AVIX, código Q,
     * alfabeto fonético y las vías reales de la plaza.
     */
    fun recognitionBiasingPhrases(
        allowedVias: Set<Int> = emptySet()
    ): List<String> {
        val phrases = mutableListOf(
            "fuga",
            "derivado",
            "fuga vía",
            "derivado vía",
            "vía",
            "placa",
            "vía placa",
            "acción vía placa",
            // Variantes que el ASR suele producir. Se incluyen como contexto,
            // pero el parser solo las corrige si la gramática operativa coincide.
            "fuga habia",
            "fuga avia",
            "fuga dia",
            "via placa",
            "via plata"
        )

        // Priorizar frases completas de vía para que el reconocedor use
        // el catálogo de la plaza como contexto, no solo como validación posterior.
        for (via in allowedVias.filter { it > 0 }.sorted()) {
            phrases += "vía $via"

            val qPhrase = numberToQPhrase(via)
            if (qPhrase.isNotBlank()) {
                phrases += "vía $qPhrase"
            }
        }

        // Contexto de placa: palabra clave + fonética esperada.
        phrases += canonicalPhoneticWords
        phrases += canonicalPhoneticWords.map { "placa $it" }

        // Los ordinales también aparecen dentro de vía y placa.
        phrases += canonicalQDigits
        phrases += canonicalQDigits.map { "placa $it" }

        return phrases.distinct().take(100)
    }

    private fun basicNormalize(text: String): String {
        val withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

        return withoutAccents
            .lowercase(Locale.ROOT)
            .replace(",", " ")
            .replace(".", " ")
            .replace(";", " ")
            .replace(":", " ")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isNumberContextToken(token: String): Boolean {
        return token.all { it.isDigit() } ||
            singleDigits.containsKey(token) ||
            spanishNumbers.containsKey(token)
    }

    private fun isPlateContextToken(
        tokens: List<String>,
        index: Int
    ): Boolean {
        val token = tokens.getOrNull(index) ?: return false

        if (singleDigits.containsKey(token)) return true
        if (phoneticAlphabet.containsKey(token)) return true
        if (token.length == 1 && token[0].isLetterOrDigit()) return true
        if (token.any { it.isDigit() } && token.all { it.isLetterOrDigit() }) return true

        if (index + 1 < tokens.size) {
            val pair = "$token ${tokens[index + 1]}"
            if (phoneticAlphabet.containsKey(pair)) return true
        }

        return false
    }

    private fun editDistance(
        a: String,
        b: String
    ): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            for (j in previous.indices) {
                previous[j] = current[j]
            }
        }

        return previous[b.length]
    }

    private fun inferPhoneticLetter(token: String): String? {
        if (token.length !in 3..10) return null

        val ranked = canonicalPhoneticMap
            .map { (word, letter) ->
                Triple(word, letter, editDistance(token, word))
            }
            .sortedBy { it.third }

        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)

        // Tolerancia según longitud. Además exigimos que la mejor coincidencia
        // sea claramente mejor que la segunda para evitar adivinanzas.
        val maxDistance = when {
            token.length <= 4 -> 1
            token.length <= 7 -> 2
            else -> 3
        }

        val isUniqueEnough =
            second == null || best.third + 1 < second.third

        return if (best.third <= maxDistance && isUniqueEnough) {
            best.second
        } else {
            null
        }
    }

    private fun resemblesVia(token: String): Boolean {
        if (token in viaContextAliases) return true

        // Fuzzy solo para palabras cortas y cuando la estructura externa ya
        // confirmó que lo siguiente parece una vía. Evita correcciones globales.
        return token.length in 2..5 &&
            editDistance(token, "via") <= 2
    }

    private fun resemblesPlaca(token: String): Boolean {
        if (token in placaContextAliases) return true
        return token.length in 4..7 &&
            editDistance(token, "placa") <= 1
    }

    /**
     * Repara errores del SpeechRecognizer usando la gramática de AVIX:
     * ACCIÓN -> VÍA -> PLACA.
     *
     * Ejemplo:
     * "fuga habia 101 plata alfa bravo..." ->
     * "fuga via 101 placa alfa bravo..."
     */
    fun normalizeOperationalContext(text: String): String {
        val basic = basicNormalize(text)
        if (basic.isBlank()) return basic

        val tokens = basic.split(" ").toMutableList()

        // 1) Acción: corrección muy restringida. "juga" solo se interpreta como
        // FUGA si inmediatamente después aparece un candidato a VÍA seguido de número.
        if (tokens.isNotEmpty() && tokens[0] in fugaContextAliases) {
            val possibleViaIndex = 1
            val possibleNumberIndex = 2
            if (
                tokens[0] != "fuga" &&
                tokens.getOrNull(possibleViaIndex)?.let { resemblesVia(it) } == true &&
                tokens.getOrNull(possibleNumberIndex)?.let { isNumberContextToken(it) } == true
            ) {
                tokens[0] = "fuga"
            }
        }

        // Ubicar una acción ya reconocida. Solo después de ella buscamos VÍA.
        val joinedBeforeVia = tokens.joinToString(" ")
        val actionIndex = when {
            aliasFuga.any { joinedBeforeVia.contains(it) } ||
                aliasDerivado.any { joinedBeforeVia.contains(it) } -> {
                tokens.indexOfFirst { token ->
                    token in aliasFuga || token in aliasDerivado || token == "fuga"
                }.coerceAtLeast(0)
            }
            else -> -1
        }

        if (actionIndex >= 0) {
            // 2) VÍA: candidato fonéticamente parecido + número inmediatamente después.
            val plateBoundary = tokens.indexOfFirst { it in placaContextAliases }
                .let { if (it >= 0) it else tokens.size }

            for (i in (actionIndex + 1) until plateBoundary) {
                val token = tokens[i]
                val next = tokens.getOrNull(i + 1)

                if (
                    resemblesVia(token) &&
                    next != null &&
                    isNumberContextToken(next)
                ) {
                    tokens[i] = "via"
                    break
                }
            }
        }

        // 3) PLACA: solo después de haber encontrado VÍA y su bloque numérico.
        val viaIndex = tokens.indexOfFirst { it == "via" || it == "carril" || it == "pista" }
        if (viaIndex >= 0) {
            for (i in (viaIndex + 1) until tokens.size) {
                val token = tokens[i]

                if (
                    resemblesPlaca(token) &&
                    isPlateContextToken(tokens, i + 1)
                ) {
                    tokens[i] = "placa"
                    break
                }
            }
        }

        return tokens.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun normalize(text: String): String {
        return normalizeOperationalContext(text)
    }

    private fun actionDetected(clean: String): Boolean {
        return aliasFuga.any { clean.contains(it) } ||
            aliasDerivado.any { clean.contains(it) }
    }

    private fun viaMarkerPosition(clean: String): Pair<Int, Int> {
        var start = -1
        var len = 0
        for (kw in viaKeywords) {
            val idx = clean.indexOf(kw)
            if (idx >= 0 && (start == -1 || idx < start)) {
                start = idx
                len = kw.length
            }
        }
        return start to len
    }

    private fun plateMarkerPosition(clean: String): Pair<Int, Int> {
        val marker = "placa "
        val idx = clean.indexOf(marker)
        return idx to if (idx >= 0) marker.length else 0
    }

    fun platePositionValidity(placa: String): List<Boolean> {
        val normalized = placa
            .uppercase(Locale.ROOT)
            .replace("-", "")
            .replace(" ", "")

        return (0..5).map { index ->
            val ch = normalized.getOrNull(index) ?: return@map false
            when (index) {
                0 -> ch.isLetter()
                1, 2 -> ch.isLetterOrDigit()
                else -> ch.isDigit()
            }
        }
    }

    fun isValidPeruPlate(placa: String): Boolean {
        val normalized = placa
            .uppercase(Locale.ROOT)
            .replace("-", "")
            .replace(" ", "")

        return normalized.length == 6 &&
            normalized.matches(Regex("[A-Z][A-Z0-9]{2}\\d{3}"))
    }

    fun analyzeCommand(
        rawText: String,
        allowedVias: Set<Int> = emptySet()
    ): AviCommandAnalysis {
        val clean = normalize(rawText)
        val parsed = parse(rawText, allowedVias)
        val hasAction = actionDetected(clean)
        val hasViaMarker = viaMarkerPosition(clean).first >= 0
        val hasPlateMarker = plateMarkerPosition(clean).first >= 0
        val viaIsAllowed = parsed.via != null &&
            parsed.via > 0 &&
            (allowedVias.isEmpty() || parsed.via in allowedVias)
        val plateValid = isValidPeruPlate(parsed.placa)

        val stage = when {
            !hasAction -> AviCommandStage.ACCION
            !hasViaMarker || !viaIsAllowed -> AviCommandStage.VIA
            !hasPlateMarker || !plateValid -> AviCommandStage.PLACA
            else -> AviCommandStage.COMPLETO
        }

        val prompt = when (stage) {
            AviCommandStage.ACCION -> "Diga la acción: FUGA o DERIVADO."
            AviCommandStage.VIA -> if (
                parsed.via != null &&
                allowedVias.isNotEmpty() &&
                parsed.via !in allowedVias
            ) {
                "La vía ${parsed.via} no corresponde a esta plaza. Repita solo la vía."
            } else {
                "No se reconoció la vía. Repita solo la vía."
            }
            AviCommandStage.PLACA -> "No se reconoció una placa válida. Repita solo la placa."
            AviCommandStage.COMPLETO -> "Comando completo."
        }

        return AviCommandAnalysis(
            stage = stage,
            parsed = parsed,
            actionDetected = hasAction,
            viaMarkerDetected = hasViaMarker,
            plateMarkerDetected = hasPlateMarker,
            platePositions = platePositionValidity(parsed.placa),
            prompt = prompt
        )
    }

    fun scoreCandidate(
        rawText: String,
        allowedVias: Set<Int> = emptySet()
    ): Int {
        val clean = normalize(rawText)
        if (clean.isBlank()) return Int.MIN_VALUE

        val analysis = analyzeCommand(rawText, allowedVias)
        val parsed = analysis.parsed
        var score = 0

        if (analysis.actionDetected) score += 20
        if (analysis.viaMarkerDetected) score += 25
        if (analysis.plateMarkerDetected) score += 35

        val actionPositions = (aliasFuga + aliasDerivado)
            .map { clean.indexOf(it) }
            .filter { it >= 0 }
        val actionPos = actionPositions.minOrNull() ?: -1
        val viaPos = viaKeywords
            .map { clean.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: -1
        val placaPos = clean.indexOf("placa ")

        if (actionPos >= 0 && viaPos > actionPos && placaPos > viaPos) {
            score += 30
        }

        if (parsed.via != null && parsed.via > 0) {
            score += 25
            if (allowedVias.isEmpty() || parsed.via in allowedVias) {
                score += 20
            } else {
                score -= 60
            }
        }

        // La placa se evalúa posición por posición, no solo como una cadena final.
        score += analysis.platePositions.count { it } * 8
        if (isValidPeruPlate(parsed.placa)) score += 50

        if (analysis.stage == AviCommandStage.COMPLETO && parsed.valido) score += 100
        score -= parsed.errores.size * 30
        score -= parsed.advertencias.size * 5

        val qTokens = setOf(
            "negativo", "negativa",
            "primero", "primer", "primera",
            "segundo", "segunda",
            "tercero", "tercer", "tercera",
            "cuarto", "cuarta", "quinto", "quinta",
            "sexto", "sexta", "septimo", "septima",
            "octavo", "octava", "noveno", "novena"
        )
        score += clean.split(" ").count { it in qTokens } * 3

        return score
    }

    fun selectBestHypothesis(
        candidates: List<String>,
        confidenceScores: FloatArray? = null,
        allowedVias: Set<Int> = emptySet()
    ): String {
        if (candidates.isEmpty()) return ""

        return candidates
            .mapIndexed { index, candidate ->
                val confidence = confidenceScores
                    ?.getOrNull(index)
                    ?.takeIf { it >= 0f }
                    ?: 0f

                val combinedScore =
                    scoreCandidate(candidate, allowedVias) + (confidence * 20f)

                candidate to combinedScore
            }
            .maxByOrNull { it.second }
            ?.first
            .orEmpty()
    }

    fun parse(
        rawText: String,
        allowedVias: Set<Int> = emptySet()
    ): ParsedCommand {
        val clean = normalize(rawText)

        if (clean.isBlank()) {
            return ParsedCommand(
                placa = "",
                via = null,
                accion = "FUGA",
                textoOriginal = rawText,
                valido = false,
                errores = listOf("No se recibió ningún texto para interpretar.")
            )
        }

        val errores = mutableListOf<String>()
        val advertencias = mutableListOf<String>()

        val hasDerivado = aliasDerivado.any { clean.contains(it) }
        val hasFuga = aliasFuga.any { clean.contains(it) }

        val accion = when {
            hasDerivado && !hasFuga -> "DERIVADO"
            hasFuga && !hasDerivado -> "FUGA"
            else -> "FUGA"
        }

        when {
            hasDerivado && hasFuga ->
                errores.add("Se detectaron FUGA y DERIVADO a la vez. Repita la acción.")
            !hasDerivado && !hasFuga ->
                errores.add("No se detectó una acción. Diga FUGA o DERIVADO.")
        }

        var viaWords = ""
        var placaWords = ""

        val (viaStart, viaKeyLen) = viaMarkerPosition(clean)
        val (placaStart, placaKeyLen) = plateMarkerPosition(clean)

        if (placaStart >= 0 && viaStart >= 0) {
            if (placaStart < viaStart) {
                placaWords = clean.substring(placaStart + placaKeyLen, viaStart).trim()
                viaWords = clean.substring(viaStart + viaKeyLen).trim()
                advertencias.add("Se recomienda dictar en el orden ACCIÓN, VÍA, PLACA.")
            } else {
                viaWords = clean.substring(viaStart + viaKeyLen, placaStart).trim()
                placaWords = clean.substring(placaStart + placaKeyLen).trim()
            }
        } else if (placaStart >= 0) {
            placaWords = clean.substring(placaStart + placaKeyLen).trim()
        } else if (viaStart >= 0) {
            viaWords = clean.substring(viaStart + viaKeyLen).trim()
        } else {
            viaWords = clean
        }

        for (alias in aliasFuga + aliasDerivado) {
            placaWords = placaWords.replace(Regex("\\b${Regex.escape(alias)}\\b"), " ").trim()
            viaWords = viaWords.replace(Regex("\\b${Regex.escape(alias)}\\b"), " ").trim()
        }

        val placaParsed = parsePlaca(placaWords)
        when {
            placaStart < 0 ->
                errores.add("No se detectó la palabra PLACA.")
            placaParsed.isBlank() ->
                errores.add("No se detectó la placa del vehículo.")
            !isValidPeruPlate(placaParsed) ->
                errores.add(
                    "La placa detectada ($placaParsed) no cumple el formato esperado: " +
                        "1 letra, 2 caracteres alfanuméricos y 3 números."
                )
        }

        val viaParsed = parseVia(viaWords, clean)
        when {
            viaStart < 0 ->
                errores.add("No se detectó la palabra VÍA.")
            viaParsed == null || viaParsed <= 0 ->
                errores.add("No se detectó un número de vía válido.")
            allowedVias.isNotEmpty() && viaParsed !in allowedVias ->
                errores.add(
                    "La vía $viaParsed no está habilitada para esta plaza. " +
                        "Permitidas: ${allowedVias.sorted().joinToString(", ")}."
                )
        }

        return ParsedCommand(
            placa = placaParsed,
            via = viaParsed,
            accion = accion,
            textoOriginal = rawText,
            valido = errores.isEmpty(),
            errores = errores.distinct(),
            advertencias = advertencias.distinct()
        )
    }

    /**
     * Fusiona una corrección corta con el resultado anterior.
     * Permite decir únicamente "placa ...", "vía ..." o "derivado/fuga"
     * sin repetir todo el comando.
     */
    fun mergeCorrection(
        previous: ParsedCommand,
        rawCorrection: String,
        allowedVias: Set<Int> = emptySet()
    ): ParsedCommand {
        val clean = normalize(rawCorrection)
        val fresh = parse(rawCorrection, allowedVias)

        val hasAction = actionDetected(clean)
        val hasVia = viaMarkerPosition(clean).first >= 0
        val hasPlate = plateMarkerPosition(clean).first >= 0

        val mergedAction = if (hasAction) fresh.accion else previous.accion
        val mergedVia = if (hasVia) fresh.via else previous.via
        val mergedPlate = if (hasPlate) fresh.placa else previous.placa

        val errores = mutableListOf<String>()

        val previousActionValid = previous.errores.none {
            it.contains("acción", ignoreCase = true) ||
                it.contains("accion", ignoreCase = true)
        }
        val previousViaValid = previous.errores.none {
            it.contains("vía", ignoreCase = true) ||
                it.contains("via", ignoreCase = true)
        }
        val previousPlateValid = previous.errores.none {
            it.contains("placa", ignoreCase = true)
        }

        if (
            mergedAction !in setOf("FUGA", "DERIVADO") ||
            (!hasAction && !previousActionValid)
        ) {
            errores.add("No se detectó una acción válida.")
        }

        if (
            mergedVia == null ||
            mergedVia <= 0 ||
            (!hasVia && !previousViaValid)
        ) {
            errores.add("No se detectó una vía válida.")
        } else if (allowedVias.isNotEmpty() && mergedVia !in allowedVias) {
            errores.add(
                "La vía $mergedVia no está habilitada para esta plaza. " +
                    "Permitidas: ${allowedVias.sorted().joinToString(", ")}."
            )
        }

        if (
            !isValidPeruPlate(mergedPlate) ||
            (!hasPlate && !previousPlateValid)
        ) {
            errores.add("La placa no cumple el formato esperado.")
        }

        val original = listOf(previous.textoOriginal, rawCorrection)
            .filter { it.isNotBlank() }
            .joinToString(" | corrección: ")

        return ParsedCommand(
            placa = mergedPlate,
            via = mergedVia,
            accion = mergedAction,
            textoOriginal = original,
            valido = errores.isEmpty(),
            errores = errores,
            advertencias = emptyList()
        )
    }

    private fun parseVia(
        viaSnippet: String,
        fullCleanText: String
    ): Int? {
        val target = if (viaSnippet.isNotBlank()) {
            viaSnippet
        } else {
            val direct = Regex(
                "(?:via|carril|numero)\\s+(\\d+)",
                RegexOption.IGNORE_CASE
            ).find(fullCleanText)

            if (direct != null) {
                return direct.groupValues[1].toIntOrNull()
            }
            ""
        }

        val numberTokens = mutableListOf<String>()
        var numberStarted = false

        for (token in target.split(" ").filter { it.isNotBlank() }) {
            val isJoiner = token == "y" || token == "numero" || token == "de"
            val isDirectDigits = token.all { it.isDigit() }
            val isNumberWord =
                singleDigits.containsKey(token) ||
                    spanishNumbers.containsKey(token)

            when {
                isDirectDigits || isNumberWord -> {
                    numberStarted = true
                    numberTokens.add(token)
                }
                isJoiner && numberStarted -> numberTokens.add(token)
                isJoiner && !numberStarted -> continue
                numberStarted -> break
                else -> continue
            }
        }

        val tokens = numberTokens.filter {
            it != "y" && it != "numero" && it != "de"
        }

        if (tokens.isEmpty()) return null

        val areAllDigitLike = tokens.all { token ->
            token.all { it.isDigit() } || singleDigits.containsKey(token)
        }

        if (areAllDigitLike) {
            if (tokens.size == 1 && tokens[0].all { it.isDigit() }) {
                return tokens[0].toIntOrNull()
            }

            val digitString = tokens.joinToString("") { token ->
                when {
                    token.all { it.isDigit() } -> token
                    else -> singleDigits[token]?.toString().orEmpty()
                }
            }

            return digitString.toIntOrNull()
        }

        val compuesto = convertWordsToNumber(numberTokens.joinToString(" "))
        return if (compuesto > 0) compuesto else null
    }

    private fun convertWordsToNumber(phrase: String): Int {
        val tokens = phrase
            .split(" ")
            .filter { it.isNotBlank() && it != "y" }

        var current = 0
        var total = 0

        for (token in tokens) {
            val value = spanishNumbers[token] ?: continue
            when {
                value == 1000 -> {
                    total += if (current == 0) 1000 else current * 1000
                    current = 0
                }
                value == 100 -> {
                    current = if (current == 0) 100 else current + 100
                }
                value >= 100 -> current += value
                else -> current += value
            }
        }

        total += current
        return total
    }

    private fun parsePlaca(placaSnippet: String): String {
        val target = placaSnippet.trim()
        if (target.isBlank()) return ""

        val compactCandidate = target
            .uppercase(Locale.ROOT)
            .replace("-", "")
            .replace(" ", "")

        Regex("[A-Z][A-Z0-9]{2}\\d{3}")
            .find(compactCandidate)
            ?.value
            ?.let { return it }

        val plateBuilder = StringBuilder()

        val tokens = target
            .split(" ")
            .filter {
                it.isNotBlank() &&
                    it != "y" &&
                    it != "guion" &&
                    it != "menos"
            }

        var index = 0
        while (index < tokens.size && plateBuilder.length < 6) {
            val token = tokens[index]

            // Primero intentar alias fonéticos de dos palabras:
            // "ve grande", "doble ve", "i griega", "x ray", etc.
            if (index + 1 < tokens.size) {
                val pair = "$token ${tokens[index + 1]}"
                val mappedPair = phoneticAlphabet[pair]
                if (mappedPair != null) {
                    plateBuilder.append(mappedPair)
                    index += 2
                    continue
                }
            }

            if (token.all { it.isDigit() }) {
                for (digit in token) {
                    if (plateBuilder.length >= 6) break
                    plateBuilder.append(digit)
                }
                index++
                continue
            }

            val mappedDigit = singleDigits[token]
            if (mappedDigit != null) {
                plateBuilder.append(mappedDigit)
                index++
                continue
            }

            val mappedLetter = phoneticAlphabet[token]
            if (mappedLetter != null) {
                plateBuilder.append(mappedLetter)
                index++
                continue
            }

            // Solo en las primeras tres posiciones de una placa permitimos
            // inferencia fonética aproximada. Las posiciones 4-6 son numéricas.
            if (plateBuilder.length <= 2) {
                val inferredLetter = inferPhoneticLetter(token)
                if (inferredLetter != null) {
                    plateBuilder.append(inferredLetter)
                    index++
                    continue
                }
            }

            if (token.length == 1 && token[0].isLetter()) {
                plateBuilder.append(token.uppercase(Locale.ROOT))
                index++
                continue
            }

            if (
                token.length in 2..6 &&
                token.all { it.isLetterOrDigit() } &&
                token.any { it.isDigit() }
            ) {
                token.uppercase(Locale.ROOT).forEach { ch ->
                    if (plateBuilder.length < 6) {
                        plateBuilder.append(ch)
                    }
                }
            }

            index++
        }

        return plateBuilder.toString().take(6)
    }
}
