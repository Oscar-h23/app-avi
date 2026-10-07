package com.example.parser

import com.example.model.ParsedCommand
import java.text.Normalizer
import java.util.Locale

object AviParser {

    // Diccionario explícito de alfabeto fonético OTAN y variantes a letras
    private val phoneticAlphabet = mapOf(
        "alfa" to "A", "alpha" to "A",
        "bravo" to "B",
        "charlie" to "C", "charli" to "C",
        "delta" to "D",
        "echo" to "E", "eco" to "E",
        "foxtrot" to "F", "fox" to "F",
        "golf" to "G",
        "hotel" to "H",
        "india" to "I",
        "juliet" to "J", "julieta" to "J",
        "kilo" to "K",
        "lima" to "L",
        "mike" to "M", "maik" to "M",
        "november" to "N", "noviembre" to "N",
        "oscar" to "O",
        "papa" to "P",
        "quebec" to "Q", "quebek" to "Q",
        "romeo" to "R",
        "sierra" to "S",
        "tango" to "T",
        "uniform" to "U", "uniforme" to "U",
        "victor" to "V",
        "whiskey" to "W", "whisky" to "W", "wisky" to "W",
        "xray" to "X", "ray" to "X", "equis" to "X",
        "yankee" to "Y", "yanki" to "Y",
        "zulu" to "Z",
        // Letras estándar en español
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

    private val singleDigits = mapOf(
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

    // Alias configurables para acciones
    private val aliasFuga = listOf("fuga", "fugado", "se dio a la fuga", "se fugo", "evasion")
    private val aliasDerivado = listOf("derivado", "derivar", "desvio", "desviado", "derivacion", "derivada")

    /**
     * Normaliza el texto removiendo tildes, signos de puntuación y espacios redundantes
     */
    fun normalize(text: String): String {
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

    /**
     * Interpreta un comando dictado localmente sin depender de ninguna IA externa.
     * Ejemplo: "Placa alfa bravo charlie uno dos tres, vía uno cinco uno, fuga"
     * -> Placa: ABC123, Vía: 151, Acción: FUGA
     */
    fun parse(rawText: String): ParsedCommand {
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

        // 1. Identificar Acción (FUGA o DERIVADO)
        var accion = "FUGA"
        var accionDetectada = false

        val hasDerivado = aliasDerivado.any { clean.contains(it) }
        val hasFuga = aliasFuga.any { clean.contains(it) }

        if (hasDerivado && !hasFuga) {
            accion = "DERIVADO"
            accionDetectada = true
        } else if (hasFuga && !hasDerivado) {
            accion = "FUGA"
            accionDetectada = true
        } else if (hasDerivado && hasFuga) {
            // Ambigüedad detectada
            accion = "FUGA"
            advertencias.add("Ambigüedad en acción detectada (menciona fuga y derivado). Se asignó FUGA por defecto.")
        } else {
            accion = "FUGA"
            advertencias.add("No se mencionó acción explícita (FUGA o DERIVADO). Se sugiere revisar.")
        }

        // 2. Extraer secciones de Placa y Vía
        var viaWords = ""
        var placaWords = ""

        val viaKeywords = listOf("via ", "carril ", "pista ", "numero ")
        // La placa solo se interpreta cuando el usuario dice explícitamente "placa".
        val placaKeywords = listOf("placa ")

        var viaStart = -1
        var viaKeyLen = 0
        for (kw in viaKeywords) {
            val idx = clean.indexOf(kw)
            if (idx != -1 && (viaStart == -1 || idx < viaStart)) {
                viaStart = idx
                viaKeyLen = kw.length
            }
        }

        var placaStart = -1
        var placaKeyLen = 0
        for (kw in placaKeywords) {
            val idx = clean.indexOf(kw)
            if (idx != -1 && (placaStart == -1 || idx < placaStart)) {
                placaStart = idx
                placaKeyLen = kw.length
            }
        }

        if (placaStart != -1 && viaStart != -1) {
            if (placaStart < viaStart) {
                // Formato: "placa ... via ..."
                placaWords = clean.substring(placaStart + placaKeyLen, viaStart).trim()
                viaWords = clean.substring(viaStart + viaKeyLen).trim()
            } else {
                // Formato: "via ... placa ..."
                viaWords = clean.substring(viaStart + viaKeyLen, placaStart).trim()
                placaWords = clean.substring(placaStart + placaKeyLen).trim()
            }
        } else if (placaStart != -1) {
            placaWords = clean.substring(placaStart + placaKeyLen).trim()
        } else if (viaStart != -1) {
            viaWords = clean.substring(viaStart + viaKeyLen).trim()
            // No intentar inferir una placa si no se dijo explícitamente "placa".
            placaWords = ""
        } else {
            // Sin la palabra "placa", no se interpreta ninguna matrícula.
            placaWords = ""
            viaWords = clean
        }

        // Limpiar palabras de acción de las subcadenas para no contaminar placa o vía
        for (alias in (aliasFuga + aliasDerivado)) {
            placaWords = placaWords.replace(Regex("\\b$alias\\b"), " ").trim()
            viaWords = viaWords.replace(Regex("\\b$alias\\b"), " ").trim()
        }

        // 3. Parser de Placa
        val placaParsed = parsePlaca(placaWords)
        if (placaParsed.isBlank()) {
            errores.add("No se detectó la placa del vehículo. Por favor dictar o ingresar la placa.")
        } else if (placaParsed.length < 6) {
            advertencias.add("La placa detectada ($placaParsed) parece incompleta. Se esperan 6 caracteres (ej. ABC123).")
        }

        // 4. Parser de Vía
        val viaParsed = parseVia(viaWords, clean)
        if (viaParsed == null || viaParsed <= 0) {
            errores.add("No se detectó un número de vía válido. Por favor especificar la vía.")
        }

        val esValido = errores.isEmpty()

        return ParsedCommand(
            placa = placaParsed,
            via = viaParsed,
            accion = accion,
            textoOriginal = rawText,
            valido = esValido,
            errores = errores,
            advertencias = advertencias
        )
    }

    private fun parseVia(viaSnippet: String, fullCleanText: String): Int? {
        val target = if (viaSnippet.isNotBlank()) viaSnippet else {
            val direct = Regex("(?:via|carril|numero)\\s+(\\d+)", RegexOption.IGNORE_CASE).find(fullCleanText)
            if (direct != null) return direct.groupValues[1].toIntOrNull()
            ""
        }

        // Si contiene dígitos numéricos directos: "151"
        val directDigits = Regex("\\b\\d+\\b").find(target)
        if (directDigits != null) {
            return directDigits.value.toIntOrNull()
        }

        val tokens = target.split(" ").filter { it.isNotBlank() && it != "y" && it != "numero" && it != "de" }
        if (tokens.isEmpty()) return null

        // Caso A: Dígito individual a dígito ("uno cinco uno" -> 151)
        val areAllSingleDigits = tokens.all { singleDigits.containsKey(it) }
        if (areAllSingleDigits && tokens.isNotEmpty()) {
            val digitString = tokens.map { singleDigits[it] }.joinToString("")
            return digitString.toIntOrNull()
        }

        // Caso B: Número compuesto en palabras ("ciento cincuenta y uno" -> 151)
        val compuesto = convertWordsToNumber(target)
        return if (compuesto > 0) compuesto else null
    }

    private fun convertWordsToNumber(phrase: String): Int {
        val tokens = phrase.split(" ").filter { it.isNotBlank() && it != "y" }
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
                value >= 100 -> {
                    current += value
                }
                else -> {
                    current += value
                }
            }
        }
        total += current
        return total
    }

    private fun parsePlaca(placaSnippet: String): String {
        val target = if (placaSnippet.isNotBlank()) placaSnippet else return ""

        // Caso 1: Placa ya en formato alfanumérico compacto (ej. "ABC123", "ABC-123", "BTL245").
        // Se busca únicamente dentro del fragmento posterior a la palabra "placa".
        val directAlphanumeric = Regex("\\b([a-z]{3})[- ]?(\\d{3})\\b", RegexOption.IGNORE_CASE).find(target)
        if (directAlphanumeric != null) {
            val letters = directAlphanumeric.groupValues[1].uppercase(Locale.ROOT)
            val digits = directAlphanumeric.groupValues[2]
            return "$letters$digits"
        }

        // Caso 2: Deletreo fonético ("alfa bravo charlie uno dos tres" -> "ABC123")
        val tokens = target.split(" ").filter { it.isNotBlank() && it != "y" && it != "guion" && it != "menos" }
        val lettersBuilder = StringBuilder()
        val digitsBuilder = StringBuilder()

        for (token in tokens) {
            // Dígito directo
            if (token.all { it.isDigit() }) {
                digitsBuilder.append(token)
                continue
            }

            // Dígito en palabra
            if (singleDigits.containsKey(token)) {
                digitsBuilder.append(singleDigits[token])
                continue
            }

            // Letra fonética
            val mapped = phoneticAlphabet[token]
            if (mapped != null) {
                if (lettersBuilder.length < 3) {
                    lettersBuilder.append(mapped)
                }
                continue
            }

            // Si es letra directa solitaria
            if (token.length == 1 && token[0].isLetter()) {
                if (lettersBuilder.length < 3) {
                    lettersBuilder.append(token.uppercase(Locale.ROOT))
                }
            }
        }

        val letters = lettersBuilder.toString()
        val digits = digitsBuilder.toString()

        // Una placa válida debe contener letras (no inventar placa a partir de solo números)
        return if (letters.isNotBlank() && digits.isNotBlank()) {
            "$letters$digits"
        } else if (letters.isNotBlank()) {
            letters
        } else {
            ""
        }
    }
}
