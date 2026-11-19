// Minimal on-device LLM wrapper around llama.cpp. Shared by the Android JNI
// bridge and the host test harness (tools/host_llm) so the exact same code is
// exercised on the build machine.
#pragma once
#include <string>
#include <vector>
#include <atomic>
#include <functional>

struct llama_model;
struct llama_context;

std::string apply_template(llama_model *model, const std::string &sys, const std::string &user);

class LlmCore {
public:
    ~LlmCore();
    bool load(const std::string &path, int n_threads, int n_ctx);
    // Builds a chat prompt with the model's own chat template, then generates
    // at most max_tokens tokens. If grammar is non-empty, output is constrained
    // by that GBNF grammar (root rule "root"). Greedy decoding: deterministic.
    std::string complete(const std::string &system, const std::string &user,
                         const std::string &grammar, int max_tokens);
    // Free text streamed through on_text (whole UTF-8 characters only); temp > 0 samples instead of greedy decoding.
    // gen_cap is the generation time limit for this call (a long story gets a big runaway-only cap).
    std::string generate(const std::string &system, const std::string &user, const std::string &grammar, int max_tokens,
                         float temp, double gen_cap, const std::function<bool(const std::string &)> &on_text);
    bool last_hit_limit = false;   // generation stopped at max_tokens (not at end-of-turn)
    std::string last_error() const { return err_; }
    /** Layers on the GPU after load (0 = CPU; only the Windows program's CUDA / Vulkan builds offload). */
    int gpu_layers() const { return gpu_layers_; }
    // Stops the running complete() (any thread, lock-free): prompt evaluation is interrupted inside ggml through
    // llama's abort callback, generation at the next token. complete() then returns what it has ("" mid-prompt).
    void cancel() { abort_.store(true); }
    void reset() { abort_.store(false); }
    // Hard caps so a reply can never hang: prompt evaluation and generation each have their own time limit.
    double prompt_cap_ms = 60000, gen_cap_ms = 45000;
    bool should_abort() const;
    std::string build_prompt(const std::string &sys, const std::string &user) const;
    std::string last_prompt_text;
    double last_prompt_ms = 0, last_gen_ms = 0;
    int last_prompt_tokens = 0, last_reused_tokens = 0, last_gen_tokens = 0;
private:
    llama_model *model_ = nullptr;
    llama_context *ctx_ = nullptr;
    std::vector<int> cached_;   // tokens currently in the KV cache (seq 0)
    std::string err_;
    int gpu_layers_ = 0;
    bool make_context(int n_ctx);
    int n_threads_ = 4;
    std::atomic<bool> abort_{false};
    double deadline_ms_ = 0;
    size_t sent_ = 0;
};
