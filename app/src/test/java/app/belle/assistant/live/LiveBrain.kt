package app.belle.assistant.live

import app.belle.assistant.core.*

/** The Brain exactly as AssistantRuntime wires it (defaults for name, sleep phrases and personality). */
object LiveBrain {
    fun make(rules: () -> RuleParser, llm: LlmBackend, router: ActionRouter, roleplay: Boolean, mem: MemoryStore) = Brain(
        rules = rules, llm = { llm }, router = router, assistantName = { "Belle" },
        sleepPhrases = { listOf("goodnight belle", "good night belle", "thanks belle that's all", "go to sleep", "that's all", "that is all", "goodbye", "stop listening", "never mind") },
        persona = { PersonaPrompt.defaults("Belle") },
        roleplay = { roleplay }, memory = { mem },
    )
}
