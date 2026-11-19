// Host harness: reads system prompt, grammar and one user utterance per line,
// prints the model's constrained JSON output. Same LlmCore as the app.
#include "llm_core.h"
#include <fstream>
#include <iostream>
#include <sstream>
#include <cstdlib>
static std::string slurp(const char *p) { std::ifstream f(p); std::stringstream s; s << f.rdbuf(); return s.str(); }
static std::string unesc(const std::string &in) {
    std::string o; for (size_t i = 0; i < in.size(); i++) {
        if (in[i] == '\\' && i + 1 < in.size()) { char c = in[++i]; o += c == 'n' ? '\n' : c == 't' ? '\t' : c; } else o += in[i]; }
    return o;
}
static std::string esc(const std::string &in) {
    std::string o; for (char c : in) { if (c == '\\') o += "\\\\"; else if (c == '\n') o += "\\n"; else if (c == '\t') o += "\\t"; else o += c; }
    return o;
}
int main(int argc, char **argv) {
    // Serve mode for the JVM full-pipeline harness: host_llm model.gguf --serve [n_ctx]
    // Request = 4 lines (system, user, grammar escaped with \\n, \\\\; then max_tokens); reply = 1 escaped line.
    if (argc >= 3 && std::string(argv[2]) == "--serve") {
        LlmCore core;
        if (!core.load(argv[1], 4, argc > 3 ? atoi(argv[3]) : 4096)) { std::cerr << core.last_error() << "\n"; return 1; }
        std::cout << "READY" << std::endl;
        std::string a, b, c, d;
        while (std::getline(std::cin, a) && std::getline(std::cin, b) && std::getline(std::cin, c) && std::getline(std::cin, d)) {
            bool stream = !d.empty() && d[0] == 'S';
            // Stream mode prints every piece as it is generated ("P<piece>"), then "D" + L|E (limit / end) + the whole text.
            std::string out = stream ? core.generate(unesc(a), unesc(b), "", atoi(d.c_str() + 1), 0.8f, 1800000,
                                                     [](const std::string &piece) { std::cout << "P" << esc(piece) << std::endl; return true; })
                                     : core.complete(unesc(a), unesc(b), unesc(c), atoi(d.c_str()));
            if (stream) out = std::string("D") + (core.last_hit_limit ? "L" : "E") + out;
            std::cerr << "[serve] " << (stream ? "stream " : "") << "gen " << core.last_gen_tokens << " tok, prompt " << core.last_prompt_tokens << " tok, reused " << core.last_reused_tokens << ", gen " << (int)core.last_gen_ms << "ms" << std::endl;
            std::cout << esc(out) << std::endl;
        }
        return 0;
    }
    if (argc < 5) { std::cerr << "usage: host_llm model.gguf system.txt grammar.gbnf utterances.txt [n_ctx] [max_tokens]\n"; return 1; }
    int n_ctx = argc > 5 ? atoi(argv[5]) : 4096, max_tok = argc > 6 ? atoi(argv[6]) : 160;
    LlmCore core;
    if (!core.load(argv[1], 4, n_ctx)) { std::cerr << core.last_error() << "\n"; return 1; }
    std::string sys = slurp(argv[2]), gram = slurp(argv[3]);
    std::ifstream u(argv[4]);
    std::string line;
    while (std::getline(u, line)) {
        for (size_t p = 0; (p = line.find("\\n", p)) != std::string::npos; ) line.replace(p, 2, "\n");
        if (line.empty()) continue;
        std::string out = core.complete(sys, line, gram, max_tok);
        std::cerr << "---- prompt (" << core.last_prompt_tokens << " tokens) head/tail:\n" << core.last_prompt_text.substr(0, 160) << "\n...\n"
                  << core.last_prompt_text.substr(core.last_prompt_text.size() > 400 ? core.last_prompt_text.size() - 400 : 0) << "\n----\n";
        std::cout << line << "\t" << out << "\t" << core.last_prompt_tokens << "/" << core.last_reused_tokens
                  << "\t" << (int)core.last_prompt_ms << "ms\t" << (int)core.last_gen_ms << "ms"
                  << (core.last_error().empty() ? "" : "\tERR " + core.last_error()) << std::endl;
    }
}
