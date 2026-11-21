package com.myokooo.ceir

object ImeiUtils {

    // ICCID/MEID/PSN/S/N/EID စတဲ့ Label တွေကို ရှာတဲ့ Regex
    // FIX: "IMEI/S/N:" လို လိုင်းကို မကျော်စေဖို့ — IMEI label ပါတဲ့ လိုင်းဆို skip မလုပ်
    private val SKIP_LABEL_REGEX = Regex(
        """ICCID|MEID|PSN|\bS/?N\b|EID""",
        RegexOption.IGNORE_CASE
    )

    // "IMEI" ဆိုတဲ့ Label ကို ရှာတဲ့ Regex
    private val IMEI_LABEL_REGEX = Regex(
        """IMEI""",
        RegexOption.IGNORE_CASE
    )

    // "IMEI1" သို့မဟုတ် "IMEI2" ဆိုတဲ့ Slot Hint ကို ရှာတဲ့ Regex
    private val IMEI_SLOT_REGEX = Regex(
        """IMEI\s*([12])""",
        RegexOption.IGNORE_CASE
    )

    // နံပါတ် ၁၅ လုံးကို ရှာတဲ့ Regex (Slash, Dash, Space တွေ ပါနိုင်)
    private val IMEI_NUMBER_REGEX = Regex(
        """(\d[\d\s\-/]{13,}\d)"""
    )

    private val CLEAN_REGEX = Regex("""[\s\-/]""")

    /**
     * OCR ဖတ်လိုက်တဲ့ စာသားထဲကနေ IMEI တွေကို ရှာထုတ်ပေးတယ်။
     *
     * lines: (line text, line top in crop coordinates or null).
     * top ကို "IMEI1/IMEI2 label မပါရင် အပေါ်ဆုံး IMEI က IMEI 1" rule အတွက် သုံးတယ်။
     *
     * FIXES vs old version:
     * - findAll: တစ်လိုင်းထဲ IMEI နှစ်ခု ပါရင် နှစ်ခုလုံး ရ
     * - 30-digit ဆက်နေရင် 15 လုံးချင်း ခွဲ (ဒုတိယ IMEI မပျောက်)
     * - "IMEI/S/N:" လို လိုင်း မကျော်
     * - line top သိမ်း → label မဲ့ရင် အပေါ်ဆုံးကို IMEI 1 အဖြစ် သတ်မှတ်နိုင်
     */
    fun extractImeis(text: String): List<DetectedImei> =
        extractImeis(text.split(Regex("""\r?\n""")).map { it to null })

    fun extractImeis(lines: List<Pair<String, Int?>>): List<DetectedImei> {
        val result = mutableListOf<DetectedImei>()
        val seen = mutableSetOf<String>()

        // IMEI တစ်ခုချင်းစီရဲ့ Slot Hint ကို သိမ်းဖို့ Map
        val slotHints = mutableMapOf<String, Int>()

        // လိုင်းတစ်လိုင်းချင်း စစ်
        var currentSlot: Int? = null

        for ((line, lineTop) in lines) {
            val hasImeiLabel = IMEI_LABEL_REGEX.containsMatchIn(line)

            // FIX: IMEI label မပါတဲ့ လိုင်းမှာသာ skip-label စစ်
            // ("IMEI/S/N:" လို IMEI ပါတဲ့ လိုင်း မကျော်တော့)
            if (!hasImeiLabel && SKIP_LABEL_REGEX.containsMatchIn(line)) continue

            // IMEI1 ဒါမှမဟုတ် IMEI2 ပါရင် Slot Hint ကို သတ်မှတ်
            val slotMatch = IMEI_SLOT_REGEX.find(line)
            if (slotMatch != null) {
                currentSlot = slotMatch.groupValues[1].toIntOrNull()
            }

            // IMEI ပါတဲ့ လိုင်း ဒါမှမဟုတ် Slot သတ်မှတ်ထားတဲ့ လိုင်း ဖြစ်ရမယ်
            if (!hasImeiLabel && currentSlot == null) continue

            // FIX: findAll — လိုင်းထဲက နံပါတ်အားလုံး ရှာ
            for (numberMatch in IMEI_NUMBER_REGEX.findAll(line)) {
                // Slash, Dash, Space တွေကို ဖယ်ပြီး နံပါတ်ပဲ ကျန်အောင် လုပ်
                val cleaned = numberMatch.groupValues[1].replace(CLEAN_REGEX, "")

                // FIX: ရှည်နေရင် 15 လုံးချင်း ခွဲ (concatenated IMEI ၂ ခု)
                val chunks = if (cleaned.length > 15) cleaned.chunked(15) else listOf(cleaned)

                for (chunk in chunks) {
                    // နံပါတ် ၁၅ လုံး ပြည့်မပြည့် စစ်
                    if (chunk.length == 15 && chunk !in seen && isValidImei(chunk)) {
                        seen.add(chunk)
                        result.add(DetectedImei(chunk, currentSlot, lineTop))
                        if (currentSlot != null) {
                            slotHints[chunk] = currentSlot
                            currentSlot = null
                        }
                    }
                }
            }
        }

        // Slot Hint တွေ ပြန်ထည့် (top ကတော့ အတိုင်းထား)
        return result.map { imei ->
            imei.copy(slotHint = slotHints[imei.value] ?: imei.slotHint)
        }
    }

    // Item 10 (barcode hybrid): "IMEI1"/"IMEI2" label → slot 1/2, any form/case.
    fun findSlotLabel(text: String): Int? =
        IMEI_SLOT_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * IMEI မှန်မမှန် Luhn Algorithm နဲ့ စစ်ဆေးတယ်။
     */
    fun isValidImei(imei: String): Boolean {
        if (!Regex("""^\d{15}$""").matches(imei)) return false
        var sum = 0
        for (i in 0 until 15) {
            var d = imei[i].digitToInt()
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }
}

/**
 * ရှာတွေ့တဲ့ IMEI အတွက် Data Class
 * @param top line ရဲ့ အပေါ်ဆုံး coordinate (crop ထဲမှာ); null ဆို မသိ
 */
data class DetectedImei(
    val value: String,
    val slotHint: Int? = null,
    val top: Int? = null
)
