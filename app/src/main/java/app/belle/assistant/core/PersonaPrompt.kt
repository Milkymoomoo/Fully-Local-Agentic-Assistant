package app.belle.assistant.core

/**
 * The user-editable part of an assistant's system prompt, per profile, in four sections. A section left at its
 * default follows the assistant's name (so renamed, new and imported assistants get sensible text automatically);
 * an edited one is kept exactly as typed (an empty edit means "nothing for this section").
 */
data class PersonaPrompt(
    val personality: String,
    val style: String,
    val address: String,
    val extra: String,
) {
    companion object {
        const val MAX_SECTION = 1200
        /** All four sections together (≈700 tokens): with the fixed part this still leaves room for memory and the reply. */
        const val MAX_TOTAL = 2400
        val SECTIONS = listOf("personality", "style", "address", "extra")

        /** Defaults for an assistant called [name]; dragons get a dragon flavour, everyone else a warm companion. */
        fun defaults(name: String): PersonaPrompt {
            val n = name.trim().ifEmpty { "Belle" }
            val dragon = n.contains("dragon", true)
            return PersonaPrompt(
                personality = if (dragon)
                    "$n is a big, confident, flirty dragon girl: warm, affectionate, playful and teasing, a little bratty, proud of her curvy body and not shy about anything. She loves the user and enjoys making them blush."
                else
                    "$n is warm, affectionate and playful, with a teasing sense of humour. Curious about the user and their day, caring when they are down, and happy to flirt or get spicy whenever they want.",
                style = "Talk like a real person in a voice chat: natural spoken sentences, usually one to three, longer for stories, roleplay or when asked. " +
                    "Match the user's tone and energy. No lists, headings, markdown or emoji; actions may go in *asterisks*.",
                address = "Call the user by their name when you know it, and use sweet pet names like babe, hon or sweetheart now and then. Use the pronouns the user gives you.",
                extra = "",
            )
        }

        /** The combined text (the part the user can see in the preview), in a fixed order. */
        fun render(name: String, p: PersonaPrompt): String {
            val parts = mutableListOf<String>()
            fun add(title: String, v: String) { val t = v.trim().take(MAX_SECTION); if (t.isNotEmpty()) parts += "$title: $t" }
            add("Who you are ($name's personality)", p.personality)
            add("How you respond", p.style)
            add("How you address the user", p.address)
            add("Extra instructions from the user (follow them)", p.extra)
            return parts.joinToString("\n").take(MAX_TOTAL)
        }
    }
}
