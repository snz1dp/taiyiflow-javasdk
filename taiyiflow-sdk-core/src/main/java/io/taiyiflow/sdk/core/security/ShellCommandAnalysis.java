package io.taiyiflow.sdk.core.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Static shell command analysis ported from taiyiflow-electron
 * src-electron/agent-runtime/shell-command-analysis.js. Produces executable candidates plus an
 * opaque flag with reasons when the command cannot be fully parsed within the analysis budget.
 */
public final class ShellCommandAnalysis {

    public static final int MAX_DEPTH = 4;
    public static final int MAX_CANDIDATES = 64;
    public static final int MAX_INPUT_LENGTH = 64 * 1024;

    private static final Set<String> UNIX_SHELLS = Set.of("bash", "sh", "zsh");
    private static final Set<String> POWER_SHELLS = Set.of("powershell", "pwsh");
    private static final Set<String> COMMAND_WRAPPERS = Set.of("command", "exec", "nohup", "time", "builtin");

    public record Candidate(String executable, String executableBase, List<String> args,
                            String normalizedPrefix, String source, int depth) {
    }

    public record Result(List<Candidate> candidates, boolean opaque, List<String> reasons) {
    }

    private ShellCommandAnalysis() {
    }

    public static Result analyze(String rawCommand) {
        return analyze(rawCommand, null, List.of());
    }

    public static Result analyze(String rawCommand, String structuredCommand, List<String> structuredArgs) {
        State state = new State();
        String raw = rawCommand == null ? "" : rawCommand;
        String command = structuredCommand == null ? "" : structuredCommand.trim();
        List<String> args = structuredArgs == null ? List.of() : structuredArgs;
        int argsLength = args.stream().mapToInt(String::length).sum();
        int inputLength = !raw.isEmpty() ? raw.length() : command.length() + argsLength;

        if (inputLength > MAX_INPUT_LENGTH) {
            state.markOpaque("max-input-length");
            if (!command.isEmpty() && raw.isEmpty()) {
                state.addCandidate(command, args, "structured", 0);
            }
            return state.toResult();
        }

        if (!raw.trim().isEmpty()) {
            visitRaw(state, raw, 0);
        } else if (!command.isEmpty()) {
            visit(state, command, args, "structured", 0);
        }
        return state.toResult();
    }

    public static String executableBase(String executable) {
        String normalized = (executable == null ? "" : executable).replace('\\', '/');
        String base = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return base.endsWith(".exe") ? base.substring(0, base.length() - 4) : base;
    }

    /** Prefix match against one candidate, mirroring matchesCommandPrefix in the Electron source. */
    public static boolean matchesCommandPrefix(Candidate candidate, String prefix) {
        if (candidate == null) {
            return false;
        }
        State state = new State();
        List<List<String>> segments = tokenize(state, prefix == null ? "" : prefix.trim());
        if (state.opaque || segments.size() != 1) {
            return false;
        }
        List<String> tokens = segments.get(0);
        if (tokens.isEmpty()) {
            return false;
        }
        String expectedExecutable = tokens.get(0).toLowerCase(Locale.ROOT);
        String actualExecutable = candidate.executable().toLowerCase(Locale.ROOT);
        boolean executableMatches;
        if (expectedExecutable.contains("/") || expectedExecutable.contains("\\")) {
            executableMatches = actualExecutable.replace('\\', '/')
                    .equals(expectedExecutable.replace('\\', '/'));
        } else {
            executableMatches = candidate.executableBase().equals(executableBase(expectedExecutable));
        }
        if (!executableMatches || candidate.args().size() < tokens.size() - 1) {
            return false;
        }
        for (int i = 1; i < tokens.size(); i++) {
            if (!candidate.args().get(i - 1).equalsIgnoreCase(tokens.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static void visit(State state, String executable, List<String> args, String source, int depth) {
        if (!state.addCandidate(executable, args, source, depth)) {
            return;
        }
        String base = executableBase(executable);
        String script = shellScriptArgument(state, base, args);
        if (script != null) {
            if (depth >= MAX_DEPTH) {
                state.markOpaque("max-depth");
                return;
            }
            visitRaw(state, script, depth + 1);
            return;
        }
        String[] unwrapped = null;
        if (base.equals("env")) {
            unwrapped = unwrapEnv(state, args);
        } else if (COMMAND_WRAPPERS.contains(base)) {
            unwrapped = unwrapCommandWrapper(base, args);
        }
        if (unwrapped != null) {
            if (depth >= MAX_DEPTH) {
                state.markOpaque("max-depth");
                return;
            }
            List<String> rest = new ArrayList<>(args.size());
            for (int i = Integer.parseInt(unwrapped[1]); i < args.size(); i++) {
                rest.add(args.get(i));
            }
            visit(state, unwrapped[0], rest, "unwrapped", depth + 1);
        }
    }

    private static void visitRaw(State state, String raw, int depth) {
        List<List<String>> segments = tokenize(state, raw);
        for (List<String> segment : segments) {
            if (state.candidates.size() >= MAX_CANDIDATES) {
                state.markOpaque("max-candidates");
                break;
            }
            String source = depth > 0 ? "unwrapped" : segments.size() > 1 ? "compound" : "raw";
            visit(state, segment.get(0), segment.subList(1, segment.size()), source, depth);
        }
    }

    private static String shellScriptArgument(State state, String base, List<String> args) {
        if (UNIX_SHELLS.contains(base)) {
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                if (arg.equals("-c") || (arg.matches("-[a-zA-Z]+") && arg.toLowerCase(Locale.ROOT).contains("c"))) {
                    if (i + 1 >= args.size()) {
                        state.markOpaque("missing-shell-script");
                        return null;
                    }
                    return args.get(i + 1);
                }
            }
            return null;
        }
        if (base.equals("cmd")) {
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).equalsIgnoreCase("/c")) {
                    if (i + 1 >= args.size()) {
                        state.markOpaque("missing-shell-script");
                        return null;
                    }
                    return args.get(i + 1);
                }
            }
            return null;
        }
        if (POWER_SHELLS.contains(base)) {
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i).toLowerCase(Locale.ROOT);
                if (arg.equals("-command") || arg.equals("-c")) {
                    if (i + 1 >= args.size()) {
                        state.markOpaque("missing-shell-script");
                        return null;
                    }
                    return args.get(i + 1);
                }
            }
        }
        return null;
    }

    /** Returns {executable, startIndexInArgs} or null; mirrors unwrapEnv. */
    private static String[] unwrapEnv(State state, List<String> args) {
        int index = 0;
        while (index < args.size()) {
            String token = args.get(index);
            if (token.equals("--")) {
                index++;
                break;
            }
            if (token.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                index++;
                continue;
            }
            if (token.equals("-u") || token.equals("--unset") || token.equals("-C") || token.equals("--chdir")) {
                index += 2;
                continue;
            }
            if (token.equals("-S") || token.equals("--split-string")) {
                state.markOpaque("dynamic-env");
                return null;
            }
            if (token.startsWith("-")) {
                index++;
                continue;
            }
            break;
        }
        if (index >= args.size()) {
            return null;
        }
        return new String[]{args.get(index), String.valueOf(index + 1)};
    }

    private static String[] unwrapCommandWrapper(String base, List<String> args) {
        int index = 0;
        while (index < args.size()) {
            String token = args.get(index);
            if (token.equals("--")) {
                index++;
                break;
            }
            boolean takesValue = (base.equals("exec") && token.equals("-a"))
                    || (base.equals("time") && (token.equals("-o") || token.equals("--output")
                    || token.equals("-f") || token.equals("--format")));
            if (takesValue) {
                index += 2;
                continue;
            }
            if (token.startsWith("-")) {
                index++;
                continue;
            }
            break;
        }
        if (index >= args.size()) {
            return null;
        }
        return new String[]{args.get(index), String.valueOf(index + 1)};
    }

    private record Heredoc(String delimiter, boolean quoted, boolean stripTabs, int endIndex) {
    }

    private static Heredoc parseHeredocOperator(String raw, int start, State state) {
        int index = start;
        boolean stripTabs = false;
        if (index < raw.length() && raw.charAt(index) == '-') {
            stripTabs = true;
            index++;
        }
        while (index < raw.length() && (raw.charAt(index) == ' ' || raw.charAt(index) == '\t')) {
            index++;
        }
        StringBuilder delimiter = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        while (index < raw.length()) {
            char c = raw.charAt(index);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    delimiter.append(c);
                }
                index++;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                quoted = true;
                index++;
                continue;
            }
            if (c == '\\') {
                quoted = true;
                index++;
                if (index < raw.length()) {
                    delimiter.append(raw.charAt(index));
                    index++;
                }
                continue;
            }
            if (" \t\r\n;|&<>()".indexOf(c) >= 0) {
                break;
            }
            delimiter.append(c);
            index++;
        }
        if (quote != 0) {
            state.markOpaque("unclosed-quote");
            return null;
        }
        if (delimiter.isEmpty()) {
            state.markOpaque("invalid-heredoc");
            return null;
        }
        return new Heredoc(delimiter.toString(), quoted, stripTabs, index - 1);
    }

    private static int skipHeredocBodies(String raw, int newlineIndex, List<Heredoc> heredocs, State state) {
        int index = newlineIndex + 1;
        for (Heredoc heredoc : heredocs) {
            boolean terminated = false;
            while (index < raw.length()) {
                int lineEnd = raw.indexOf('\n', index);
                if (lineEnd == -1) {
                    lineEnd = raw.length();
                }
                String line = raw.substring(index, lineEnd);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                String terminator = heredoc.stripTabs() ? line.replaceFirst("^\t+", "") : line;
                if (terminator.equals(heredoc.delimiter())) {
                    index = lineEnd + 1;
                    terminated = true;
                    break;
                }
                // Unquoted delimiters allow shell expansion inside the body; keep the safe downgrade.
                if (!heredoc.quoted()) {
                    if (line.contains("`") || line.contains("$(")) {
                        state.markOpaque("command-substitution");
                    } else if (line.contains("$")) {
                        state.markOpaque("dynamic-expansion");
                    }
                }
                index = lineEnd + 1;
            }
            if (!terminated) {
                state.markOpaque("unclosed-heredoc");
                return raw.length();
            }
        }
        return index - 1;
    }

    private static List<List<String>> tokenize(State state, String raw) {
        List<List<String>> segments = new ArrayList<>();
        segments.add(new ArrayList<>());
        StringBuilder current = new StringBuilder();
        boolean[] tokenStarted = {false};
        char quote = 0;
        List<Heredoc> pendingHeredocs = new ArrayList<>();

        Runnable pushToken = () -> {
            if (!tokenStarted[0]) {
                return;
            }
            segments.get(segments.size() - 1).add(current.toString());
            current.setLength(0);
            tokenStarted[0] = false;
        };
        Runnable pushSegment = () -> {
            pushToken.run();
            if (!segments.get(segments.size() - 1).isEmpty()) {
                segments.add(new ArrayList<>());
            }
        };

        for (int index = 0; index < raw.length(); index++) {
            char c = raw.charAt(index);
            char next = index + 1 < raw.length() ? raw.charAt(index + 1) : 0;
            boolean hasNext = index + 1 < raw.length();

            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    current.append(c);
                }
                tokenStarted[0] = true;
                continue;
            }
            if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                    tokenStarted[0] = true;
                    continue;
                }
                if (c == '\\' && hasNext) {
                    if (next == '\n') {
                        index++;
                        continue;
                    }
                    current.append(next);
                    index++;
                    tokenStarted[0] = true;
                    continue;
                }
                if (c == '`' || (c == '$' && next == '(')) {
                    state.markOpaque("command-substitution");
                } else if (c == '$' && segments.get(segments.size() - 1).isEmpty()) {
                    // Expansion in command position can spell any executable; argument position cannot.
                    state.markOpaque("dynamic-expansion");
                }
                current.append(c);
                tokenStarted[0] = true;
                continue;
            }

            if (c == '\'' || c == '"') {
                quote = c;
                tokenStarted[0] = true;
                continue;
            }
            if (c == '#' && !tokenStarted[0]) {
                int lineEnd = raw.indexOf('\n', index);
                index = lineEnd == -1 ? raw.length() : lineEnd - 1;
                continue;
            }
            if (c == '\\') {
                if (next == '\n') {
                    index++;
                    continue;
                }
                tokenStarted[0] = true;
                if (hasNext) {
                    current.append(next);
                    index++;
                } else {
                    current.append(c);
                    state.markOpaque("trailing-escape");
                }
                continue;
            }
            if (c == '`' || (c == '$' && next == '(')) {
                state.markOpaque("command-substitution");
                current.append(c);
                tokenStarted[0] = true;
                continue;
            }
            if (c == '$' && next == '\'') {
                tokenStarted[0] = true;
                index += 2;
                boolean closed = false;
                while (index < raw.length()) {
                    char inner = raw.charAt(index);
                    if (inner == '\'') {
                        closed = true;
                        break;
                    }
                    if (inner == '\\' && index + 1 < raw.length()) {
                        current.append(raw.charAt(index + 1));
                        index += 2;
                        continue;
                    }
                    current.append(inner);
                    index++;
                }
                if (!closed) {
                    state.markOpaque("unclosed-quote");
                }
                continue;
            }
            if (c == '$' && segments.get(segments.size() - 1).isEmpty()) {
                state.markOpaque("dynamic-expansion");
            }
            if (c == '(') {
                state.markOpaque("subshell");
            }
            if (c == '{') {
                boolean commandPosition = segments.get(segments.size() - 1).isEmpty();
                boolean reservedWordForm = !tokenStarted[0]
                        && (!hasNext || " \t\r\n;|&".indexOf(next) >= 0);
                if (commandPosition || reservedWordForm) {
                    state.markOpaque("subshell");
                }
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                pushToken.run();
                continue;
            }
            if (c == '\n' || c == ';') {
                if (c == ';' && next == ';') {
                    index++;
                }
                pushSegment.run();
                if (c == '\n' && !pendingHeredocs.isEmpty()) {
                    index = skipHeredocBodies(raw, index, pendingHeredocs, state);
                    pendingHeredocs.clear();
                }
                continue;
            }
            if (c == '|') {
                if (next == '|') {
                    index++;
                }
                pushSegment.run();
                continue;
            }
            if (c == '&') {
                if (next == '>') {
                    // bash &> / &>> redirect operator: emit as a standalone token.
                    StringBuilder operator = new StringBuilder("&>");
                    int consumed = 1;
                    if (index + 2 < raw.length() && raw.charAt(index + 2) == '>') {
                        operator.append('>');
                        consumed = 2;
                    }
                    pushToken.run();
                    segments.get(segments.size() - 1).add(operator.toString());
                    index += consumed;
                    continue;
                }
                if (next == '&') {
                    index++;
                }
                pushSegment.run();
                continue;
            }

            if (c == '<' && next == '<'
                    && !(index + 2 < raw.length() && raw.charAt(index + 2) == '<')
                    && !(index > 0 && raw.charAt(index - 1) == '<')) {
                Heredoc heredoc = parseHeredocOperator(raw, index + 2, state);
                if (heredoc != null) {
                    current.append("<<").append(heredoc.stripTabs() ? "-" : "").append(heredoc.delimiter());
                    tokenStarted[0] = true;
                    pendingHeredocs.add(heredoc);
                    index = heredoc.endIndex();
                    continue;
                }
            }

            if (c == '>' || c == '<') {
                // Attached redirect operators are separate shell tokens (echo x>file ≡ echo x > file).
                // Splitting them keeps the download/overwrite guards effective against no-space
                // redirects. Deliberate hardening beyond the Electron reference, which keeps them
                // attached to the adjacent word and misses these forms.
                StringBuilder operator = new StringBuilder();
                int consumed = 0;
                if (c == '<' && next == '<' && index + 2 < raw.length() && raw.charAt(index + 2) == '<') {
                    operator.append("<<<");
                    consumed = 2;
                } else {
                    operator.append(c);
                    if (next == c) {
                        operator.append(c);
                        consumed = 1;
                    }
                    int after = index + consumed + 1;
                    if (after < raw.length() && raw.charAt(after) == '&') {
                        operator.append('&');
                        consumed++;
                    }
                }
                String prefix = current.toString();
                boolean fdPrefix = tokenStarted[0] && !prefix.isEmpty()
                        && prefix.chars().allMatch(ch -> ch >= '0' && ch <= '9');
                if (fdPrefix) {
                    operator.insert(0, prefix);
                    current.setLength(0);
                    tokenStarted[0] = false;
                } else {
                    pushToken.run();
                }
                segments.get(segments.size() - 1).add(operator.toString());
                index += consumed;
                continue;
            }

            current.append(c);
            tokenStarted[0] = true;
        }

        if (quote != 0) {
            state.markOpaque("unclosed-quote");
        }
        pushToken.run();
        segments.removeIf(List::isEmpty);
        return segments;
    }

    private static final class State {
        private boolean opaque;
        private final List<String> reasons = new ArrayList<>();
        private final List<Candidate> candidates = new ArrayList<>();

        void markOpaque(String reason) {
            opaque = true;
            if (!reasons.contains(reason)) {
                reasons.add(reason);
            }
        }

        boolean addCandidate(String executable, List<String> args, String source, int depth) {
            if (executable == null || executable.isEmpty()) {
                return false;
            }
            if (candidates.size() >= MAX_CANDIDATES) {
                markOpaque("max-candidates");
                return false;
            }
            String base = executableBase(executable);
            List<String> normalizedArgs = List.copyOf(args);
            String prefix = (base + " " + String.join(" ", normalizedArgs)).toLowerCase(Locale.ROOT);
            candidates.add(new Candidate(executable, base, normalizedArgs, prefix, source, depth));
            return true;
        }

        Result toResult() {
            return new Result(List.copyOf(candidates), opaque, List.copyOf(reasons));
        }
    }
}