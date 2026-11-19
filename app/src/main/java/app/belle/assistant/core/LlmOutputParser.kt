package app.belle.assistant.core

import org.json.JSONObject

/** Validates the LLM's JSON tool call against the tool schema. */
object LlmOutputParser {
    fun parse(raw: String): Action? = parseJson(raw) ?: salvageReply(raw)

    /** A reply cut off by the token limit (no closing quote/braces): speak what was generated instead of nothing. */
    fun salvageReply(raw: String): Action? {
        val c = ModelText.clean(raw)
        val m = Regex("\"tool\"\\s*:\\s*\"reply\"\\s*,\\s*\"args\"\\s*:\\s*\\{\\s*\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(c) ?: return null
        val text = m.groupValues[1].replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\").trim()
        if (text.isEmpty()) return null
        // End on a whole word.
        // Proper turn closing: end on the last whole sentence when there is one, else on a whole word.
        val sEnd = maxOf(text.lastIndexOf(". "), text.lastIndexOf("! "), text.lastIndexOf("? "))
        val cut = when {
            text.last() in ".!?…*" -> text
            sEnd > text.length * 3 / 4 -> text.substring(0, sEnd + 1)
            text.contains(' ') -> text.substringBeforeLast(' ') + "…"
            else -> text
        }
        return Action("reply", mapOf("text" to cut))
    }

    private fun parseJson(raw: String): Action? {
        val json = ModelText.clean(raw)
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(json.substring(start, end + 1))
            val tool = o.optString("tool")
            val spec = Tools.byName[tool] ?: return null
            val argsObj = o.optJSONObject("args") ?: JSONObject()
            val args = LinkedHashMap<String, String>()
            for (p in spec.params) {
                val v = argsObj.opt(p.name)?.toString()?.trim().orEmpty()
                val ok = when (val ty = p.type) {
                    is ParamType.Int -> v.isEmpty() || v.toIntOrNull() != null
                    is ParamType.Choice -> v.isEmpty() || v in ty.values
                    is ParamType.Text -> true
                }
                if (!ok) return null
                // Never speak template control text a model leaked into a value (<|im_end|>, <think>…, <end_of_turn>…).
                args[p.name] = if (p.type is ParamType.Text) ModelText.clean(v) else v
            }
            Action(tool, args)
        } catch (e: Exception) {
            null
        }
    }
}
