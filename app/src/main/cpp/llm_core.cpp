#include "llm_core.h"
#include "llama.h"
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <mutex>

static std::once_flag g_backend_once;

static double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

LlmCore::~LlmCore() {
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
}

bool LlmCore::should_abort() const { return abort_.load() || (deadline_ms_ > 0 && now_ms() > deadline_ms_); }
static bool abort_cb(void *data) { return static_cast<const LlmCore *>(data)->should_abort(); }

bool LlmCore::load(const std::string &path, int n_threads, int n_ctx) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
    if (ctx_) { llama_free(ctx_); ctx_ = nullptr; }
    if (model_) { llama_model_free(model_); model_ = nullptr; }
    cached_.clear();
    n_threads_ = n_threads > 0 ? n_threads : 4;
    llama_model_params mp = llama_model_default_params();
#ifdef BELLE_LLM_GPU
    // the Windows program's CUDA / Vulkan builds: every layer on the GPU (BELLE_LLM_GPU_LAYERS limits it); if the
    // model or its context doesn't fit in video memory, the same file is loaded again on the CPU
    int want = 999;
    if (const char *e = getenv("BELLE_LLM_GPU_LAYERS")) want = atoi(e);
#else
    int want = 0;  // CPU only: works on every phone, no vendor drivers
#endif
    gpu_layers_ = 0;
    for (int layers : {want, 0}) {
        if (layers < 0) continue;
        mp.n_gpu_layers = layers;
        model_ = llama_model_load_from_file(path.c_str(), mp);
        if (!model_) { if (layers == 0) { err_ = "failed to load model: " + path; return false; } continue; }
        if (make_context(n_ctx)) { gpu_layers_ = layers; return true; }
        llama_model_free(model_); model_ = nullptr;
        if (layers == 0) return false;
    }
    return false;
}

bool LlmCore::make_context(int n_ctx) {
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx > 0 ? n_ctx : 2048;
    cp.n_batch = 512;
    cp.n_threads = n_threads_;
    cp.n_threads_batch = n_threads_;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = this;
    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) { err_ = "failed to create context"; return false; }
    return true;
}

// Template name for models whose GGUF carries no chat template, from the architecture.
static const char *fallback_template(llama_model *model) {
    char arch[64] = {0};
    llama_model_meta_val_str(model, "general.architecture", arch, sizeof(arch));
    std::string a(arch);
    if (a.rfind("gemma", 0) == 0) return "gemma";     // no system role: llama.cpp merges it into the first user turn
    if (a == "llama") return "llama3";
    if (a.rfind("phi3", 0) == 0) return "phi3";
    return "chatml";                                   // Qwen family and most others
}

std::string LlmCore::build_prompt(const std::string &sys, const std::string &user) const { return apply_template(model_, sys, user); }

std::string apply_template(llama_model *model, const std::string &sys, const std::string &user) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (!tmpl) tmpl = fallback_template(model);
    llama_chat_message msgs[2] = {{"system", sys.c_str()}, {"user", user.c_str()}};
    std::vector<char> buf(sys.size() + user.size() + 1024);
    int n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int)buf.size());
    if (n > (int)buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int)buf.size());
    }
    if (n < 0) {  // template string llama.cpp doesn't recognise: use the architecture's known format
        tmpl = fallback_template(model);
        n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int)buf.size());
        if (n > (int)buf.size()) { buf.resize(n + 1); n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int)buf.size()); }
        if (n < 0) return "<|im_start|>system\n" + sys + "<|im_end|>\n<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
    }
    return std::string(buf.data(), n);
}

std::string LlmCore::complete(const std::string &system, const std::string &user,
                              const std::string &grammar, int max_tokens) {
    return generate(system, user, grammar, max_tokens, 0.0f, gen_cap_ms, nullptr);
}

// Length of the longest prefix of s that ends on a whole UTF-8 character (pieces can split a multi-byte character).
static size_t utf8_complete(const std::string &s) {
    size_t n = s.size(), i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char)s[i - 1];
        if ((c & 0xC0) != 0x80) {   // lead byte (or ASCII) at i-1
            int need = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 1;
            return (n - (i - 1) >= (size_t)need) ? n : i - 1;
        }
        i--; back++;
    }
    return n;
}

std::string LlmCore::generate(const std::string &system, const std::string &user, const std::string &grammar,
                              int max_tokens, float temp, double gen_cap, const std::function<bool(const std::string &)> &on_text) {
    err_.clear();
    last_hit_limit = false;
    deadline_ms_ = 0;   // abort_ is NOT reset here: a cancel that arrives just before a call still stops it (see reset())
    if (!ctx_) { err_ = "model not loaded"; return ""; }
    const llama_vocab *vocab = llama_model_get_vocab(model_);
    std::string prompt = apply_template(model_, system, user);

    // Templates that already start with the BOS text (Llama 3, Gemma) must not get a second BOS from the tokenizer:
    // a doubled BOS is a classic cause of gibberish output.
    bool add_bos = true;
    {
        llama_token bos = llama_vocab_bos(vocab);
        const char *bt = bos >= 0 ? llama_vocab_get_text(vocab, bos) : nullptr;
        if (bt && *bt && prompt.rfind(bt, 0) == 0) add_bos = false;
    }
    last_prompt_text = prompt;
    int n = -llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, add_bos, true);
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), toks.data(), n, add_bos, true) < 0) {
        err_ = "tokenize failed"; return "";
    }
    int n_ctx = (int)llama_n_ctx(ctx_);
    last_prompt_tokens = (int)toks.size();
    // Free-text (streamed) replies take whatever room is left; a grammar reply needs its full budget.
    if (on_text && (int)toks.size() + max_tokens >= n_ctx && n_ctx - (int)toks.size() - 8 >= 64) max_tokens = n_ctx - (int)toks.size() - 8;
    if ((int)toks.size() + max_tokens >= n_ctx) { err_ = "prompt too long for context (" + std::to_string(toks.size()) + " tokens)"; return ""; }

    // Reuse the KV cache for the shared prefix (the long system prompt with the
    // tool list is identical between requests, so only the user turn is new).
    size_t common = 0;
    while (common < cached_.size() && common < toks.size() && cached_[common] == toks[common]) common++;
    if (common == toks.size()) common--;  // must evaluate at least one token for logits
    llama_memory_t mem = llama_get_memory(ctx_);
    llama_memory_seq_rm(mem, 0, (llama_pos)common, -1);
    cached_.resize(common);

    double t0 = now_ms();
    deadline_ms_ = t0 + prompt_cap_ms;
    last_reused_tokens = (int)common;
    last_prompt_tokens = (int)toks.size();
    for (size_t i = common; i < toks.size();) {
        int nb = (int)std::min<size_t>(512, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, nb);
        if (llama_decode(ctx_, b) != 0) {
            err_ = abort_.load() ? "cancelled" : should_abort() ? "prompt took too long, stopped" : "decode failed";
            deadline_ms_ = 0; cached_.clear(); llama_memory_clear(mem, true); return "";
        }
        for (int k = 0; k < nb; k++) cached_.push_back(toks[i + k]);
        i += nb;
    }
    double t1 = now_ms();
    last_prompt_ms = t1 - t0;
    deadline_ms_ = t1 + gen_cap;

    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!g) { err_ = "grammar parse failed"; llama_sampler_free(chain); return ""; }
        llama_sampler_chain_add(chain, g);
    }
    // Guards against degenerate loops: a mild repetition penalty over the last 64 tokens (applied before the greedy
    // pick); the grammar and max_tokens bound the length; end-of-generation tokens stop it.
    if (temp > 0.0f) {
        // Long free text (stories, songs): greedy decoding falls into loops and word salad over hundreds of tokens,
        // so sample, with a wider repetition window.
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 256, 1.1f, 0.0f, 0.0f));
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t)now_ms()));
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    }

    std::string out;
    sent_ = 0;
    int gen = 0;
    for (; gen < max_tokens; gen++) {
        if (abort_.load()) { err_ = "cancelled"; break; }
        if (now_ms() - t1 > gen_cap) { err_ = "generation took too long, stopped"; break; }   // never hang a conversation
        llama_token t = llama_sampler_sample(chain, ctx_, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        char piece[256];
        int m = llama_token_to_piece(vocab, t, piece, sizeof(piece), 0, false);
        if (m > 0) out.append(piece, m);
        if (on_text) {
            size_t ok = utf8_complete(out);
            if (ok > sent_) { if (!on_text(out.substr(sent_, ok - sent_))) { sent_ = ok; err_ = "stopped by caller"; break; } sent_ = ok; }
        }
        llama_batch b = llama_batch_get_one(&t, 1);
        if (llama_decode(ctx_, b) != 0) { err_ = should_abort() ? "cancelled" : "decode failed"; cached_.clear(); llama_memory_clear(llama_get_memory(ctx_), true); break; }
        cached_.push_back(t);
    }
    llama_sampler_free(chain);
    if (gen >= max_tokens) last_hit_limit = true;
    if (on_text && sent_ < out.size()) on_text(out.substr(sent_));
    deadline_ms_ = 0;
    last_gen_tokens = gen;
    last_gen_ms = now_ms() - t1;
    return out;
}
