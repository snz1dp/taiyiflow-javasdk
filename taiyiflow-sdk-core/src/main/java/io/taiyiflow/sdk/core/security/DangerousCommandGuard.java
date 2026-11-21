package io.taiyiflow.sdk.core.security;

import io.taiyiflow.sdk.core.security.ShellCommandAnalysis.Candidate;
import io.taiyiflow.sdk.core.security.ShellCommandAnalysis.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AUTO-mode dangerous command guards ported from taiyiflow-electron
 * src-electron/agent-runtime/security.js. Messages are kept verbatim so deny reasons match the
 * Electron reference. The download guard always applies: the Java shell plugin is always sandboxed
 * (there is no shell_sandbox_mode=off escape hatch).
 */
public final class DangerousCommandGuard {

    private static final Set<String> HIGH_RISK_EXECUTABLES =
            Set.of("shutdown", "reboot", "killall", "diskpart", "sudo", "halt", "poweroff");
    private static final Set<String> SHELL_EXECUTABLES = Set.of("sh", "bash", "zsh", "ksh");
    private static final Set<String> SCRIPT_EXECUTABLES = Set.of(
            "python", "python2", "python3", "perl", "ruby", "php", "node", "nodejs",
            "powershell", "powershell.exe", "pwsh", "pwsh.exe");
    private static final Set<String> SENSITIVE_READ_EXECUTABLES = Set.of(
            "cat", "less", "more", "head", "tail", "vi", "vim", "nano", "xxd", "hexdump",
            "od", "strings", "cp", "mv", "scp", "rsync", "base64", "tee", "dd", "grep",
            "awk", "sed", "ls", "type", "get-content", "get-item", "copy-item", "import-csv");

    private static final List<String> SENSITIVE_PATH_FRAGMENTS = List.of(
            "/etc/passwd", "/etc/shadow", "/etc/gshadow", "/etc/sudoers", "/etc/master.passwd",
            ".ssh/", ".ssh\\", "id_rsa", "id_ecdsa", "id_ed25519", "id_dsa", "authorized_keys", "known_hosts",
            ".gnupg/", ".gnupg\\", ".aws/", ".aws\\", ".kube/", ".kube\\", ".docker/", ".docker\\",
            ".config/gcloud/", ".config\\gcloud\\", ".azure/", ".azure\\", ".config/azure/", ".config\\azure\\",
            ".git-credentials", ".netrc", ".npmrc", ".pypirc", "library/keychains",
            "system32\\config\\sam", "system32\\config\\system", "system32\\config\\security",
            "system32\\drivers\\etc\\hosts", "microsoft\\credentials",
            ".zshrc", ".bashrc", ".bash_profile", ".zprofile", ".profile", ".gitconfig",
            "psreadline", "consolehost_history.txt", "/.gem/", "/.terraform.d", "/.ansible",
            ".vault-token", "/.pki/", ".local/share/keyring", ".local\\share\\keyring",
            ".config/oci/", ".config\\oci\\", "/.mozilla", "/.thunderbird",
            ".config/google-chrome/", ".config/chromium/", "library/application support/google/chrome",
            "library/application support/chromium", "library/application support/firefox",
            "/etc/hosts", "/etc/fstab", "/etc/ssh", "/etc/ssl/private", "/etc/letsencrypt",
            "/etc/passwd-", "/etc/shadow-", "/etc/gshadow-", "system32\\config\\software",
            "system32\\config\\default", "system32\\winevt", "microsoft\\crypto", "microsoft\\protect");

    private static final List<String> SYSTEM_CRITICAL_PATHS = List.of(
            "/etc/hosts", "/etc/passwd", "/etc/shadow", "/etc/gshadow", "/etc/sudoers",
            "/etc/fstab", "/etc/hostname", "/etc/resolv.conf", "/etc/sysctl.conf",
            "/etc/crontab", "/etc/anacrontab", "/etc/master.passwd", "/etc/hosts.allow",
            "/etc/hosts.deny", "/etc/nsswitch.conf", "/etc/ssh/sshd_config");

    private static final List<String> SYSTEM_DIRECTORIES = List.of(
            "/etc/", "/usr/", "/bin/", "/sbin/", "/boot/", "/dev/", "/sys/", "/proc/",
            "/lib/", "/lib64/", "/var/");

    private static final List<String> HOME_DIRECTORY_FRAGMENTS = List.of(
            "~/documents", "~/desktop", "~/downloads", "~/pictures", "~/music", "~/videos",
            "~/movies", "/home/");

    private static final List<String> HISTORY_PATHS = List.of(
            ".bash_history", ".zsh_history", ".python_history", ".lesshst", ".viminfo",
            "authorized_keys", "psreadline", "consolehost_history.txt");

    private static final List<String> SCRIPT_FILE_ACCESS_PATTERNS = List.of(
            "open(", "open (", "io.read", "fileinput", "shutil.copy", "shutil.move",
            "os.path.join", "subprocess", "open(my $fh", "open(file", "file.open",
            "file.read", "require('fs')", "require(\"fs\")", "readfilesync",
            "createreadstream", "file_get_contents", "fopen(", "get-content",
            "get-item", "copy-item", "import-csv");

    private static final List<String> SCRIPT_DESTRUCTIVE_APIS = List.of(
            "os.remove", "os.unlink", "shutil.rmtree", "os.chmod", "os.chown", "os.system",
            "subprocess.run", "subprocess.call", "subprocess.popen", "path.unlink", "path.rmdir",
            "unlinksync", "rmsync", "rmdirsync", "chmodsync", "chownsync", "fileutils.rm_rf",
            "fileutils.rm", "unlink(", "remove-item", "clear-content", "set-content",
            "stop-process", "format-volume", "clear-disk", "remove-partition", "stop-computer",
            "restart-computer", "remove-itemproperty", "clear-item");

    private static final Pattern VARIABLE_EXPANSION = Pattern.compile("\\$(?:[A-Za-z_0-9?@*#!]|\\{|\\()");
    private static final Pattern ENV_FILE = Pattern.compile(
            "(?:^|[\\\\/\\s])\\.env(?:\\.(?!example\\b|sample\\b)[a-z0-9_-]+)?(?=$|[\\\\/\\s])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MAC_HOME_ROOT = Pattern.compile("(?:^|\\s)/users/[^/\\s]+/?(?=$|\\s)");
    private static final Pattern DEV_TCP_UDP = Pattern.compile("/dev/(?:tcp|udp)/");
    private static final Pattern SH_INTERACTIVE_REDIRECT = Pattern.compile("\\b(?:ba|z)?sh\\s+-i\\s*>&");
    private static final Pattern WINDOWS_SYSTEM_PATH = Pattern.compile(
            "system32|\\\\\\\\windows\\\\|[a-z]:\\\\windows(?:\\\\|[\"']|\\s|$)|%systemroot%"
                    + "|\\\\program files(?:\\\\|[\"']|\\s|$)|\\\\programdata(?:\\\\|[\"']|\\s|$)");
    private static final Pattern WINDOWS_USER_PATH = Pattern.compile(
            "\\\\users(?:\\\\|[\"']|\\s|$)|%userprofile%"
                    + "|\\\\(?:desktop|documents|downloads|pictures|music|videos)(?:\\\\|[\"']|\\s|$)");
    private static final Pattern RM_WORD = Pattern.compile("\\brm\\s");
    private static final Pattern LEADING_REDIRECT = Pattern.compile("^\\s*>");
    private static final Pattern CURL_SHORT_OUTPUT = Pattern.compile("^-[a-z]*o(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REDIRECT_TOKEN = Pattern.compile("^(?:\\d+)?>>?(?:&)?$");
    private static final Set<String> DYNAMIC_SCRIPT_SOURCES =
            Set.of("base64", "xxd", "echo", "curl", "wget");
    private static final int MAX_DYNAMIC_NESTING = 4;

    private DangerousCommandGuard() {
    }

    /** Returns the deny message, or null when the command passes all AUTO-mode guards. */
    public static String guard(String command) {
        return guard(command, ShellCommandAnalysis.analyze(command));
    }

    public static String guard(String command, Result analysis) {
        String preview = (command == null ? "" : command).trim().toLowerCase(Locale.ROOT);
        for (Candidate candidate : analysis.candidates()) {
            String message = dangerousCommandMessage(candidate);
            if (message != null) {
                return message;
            }
        }
        String message = forkBombMessage(analysis, preview);
        if (message == null) {
            message = reverseShellMessage(analysis, preview);
        }
        if (message == null) {
            message = sensitiveFileMessage(analysis, preview);
        }
        if (message == null) {
            message = networkDownloadMessage(analysis);
        }
        if (message == null) {
            message = destructiveActionMessage(analysis, preview);
        }
        if (message == null) {
            message = scriptDestructiveMessage(analysis, preview);
        }
        if (message == null) {
            message = windowsDestructiveMessage(analysis, preview);
        }
        if (message == null) {
            message = dynamicExecutionMessage(analysis);
        }
        if (message == null) {
            message = deepNestedScriptMessage(analysis);
        }
        return message;
    }

    static String dangerousCommandMessage(Candidate candidate) {
        String base = candidate.executableBase();
        List<String> args = lowerArgs(candidate);
        if (HIGH_RISK_EXECUTABLES.contains(base) || base.startsWith("mkfs")) {
            return "检测到高风险命令 \"" + base + "\"，当前为自动模式，拒绝执行。请切换到确认模式后再执行。";
        }
        if (base.equals("dd") && args.stream().anyMatch(arg -> arg.startsWith("of=/dev/"))) {
            return "检测到高风险磁盘覆写命令 \"dd of=/dev/\"，当前为自动模式，拒绝执行。请切换到确认模式后再执行。";
        }
        if ((base.equals("del") || base.equals("rd")) && args.contains("/s")) {
            return "检测到高风险命令 \"" + base + " /s\"，当前为自动模式，拒绝执行。请切换到确认模式后再执行。";
        }
        if (base.equals("format") && args.stream().anyMatch(arg -> arg.matches("[a-z]:"))) {
            return "检测到格式化磁盘操作（format），该操作不可逆，当前为自动模式，拒绝执行。请切换到确认模式后再执行。";
        }
        if (base.equals("rm") && args.stream().anyMatch(arg ->
                arg.equals("/") || arg.equals("/*") || arg.equals("~") || arg.startsWith("~/"))) {
            return "检测到递归删除危险目标（rm），该操作不可逆，当前为自动模式，拒绝执行。请切换到确认模式后再执行。";
        }
        if (base.equals("rm") && args.stream().anyMatch(DangerousCommandGuard::hasVariableExpansion)) {
            return "检测到删除目标含未展开变量（rm），无法核实实际路径，该操作不可逆，当前为自动模式，拒绝执行。请切换到确认模式后再执行。";
        }
        return null;
    }

    private static String forkBombMessage(Result analysis, String preview) {
        Set<String> bases = candidateBases(analysis);
        if ((bases.contains(":(){") || bases.contains(":"))
                && (preview.contains(":(){") || preview.contains(": () {"))) {
            return "检测到 Fork Bomb，当前为自动模式，拒绝执行。";
        }
        if (bases.contains("perl") && preview.contains("fork while fork")) {
            return "检测到 Fork Bomb，当前为自动模式，拒绝执行。";
        }
        if (bases.stream().anyMatch(base -> base.startsWith("python")) && preview.contains("os.fork()")) {
            return "检测到 Fork Bomb，当前为自动模式，拒绝执行。";
        }
        return null;
    }

    private static String reverseShellMessage(Result analysis, String preview) {
        Set<String> bases = candidateBases(analysis);
        boolean hasShell = hasCandidate(bases, SHELL_EXECUTABLES);
        if (hasShell && (DEV_TCP_UDP.matcher(preview).find() || SH_INTERACTIVE_REDIRECT.matcher(preview).find())) {
            return "检测到反向 Shell 网络重定向，当前为自动模式，拒绝执行。";
        }
        for (Candidate candidate : analysis.candidates()) {
            List<String> args = lowerArgs(candidate);
            String base = candidate.executableBase();
            if ((base.equals("nc") || base.equals("ncat"))
                    && args.stream().anyMatch(arg -> arg.equals("-e") || arg.equals("-c"))) {
                return "检测到 nc/ncat 反向 Shell，当前为自动模式，拒绝执行。";
            }
            if (base.equals("socat") && args.stream().anyMatch(arg -> arg.startsWith("tcp"))
                    && args.stream().anyMatch(arg -> arg.startsWith("exec:"))) {
                return "检测到 socat 反向 Shell，当前为自动模式，拒绝执行。";
            }
        }
        if (hasCandidate(bases, SCRIPT_EXECUTABLES)) {
            boolean pythonSocket = bases.stream().anyMatch(base -> base.startsWith("python"))
                    && (preview.contains("os.dup2(") || preview.contains("socket.socket(")
                    || preview.contains("import socket,subprocess,os"));
            if (pythonSocket
                    || preview.contains("use socket;") && preview.contains("socket(")
                    || preview.contains("tcpsocket.open") || preview.contains("tcpsocket.new")
                    || preview.contains("fsockopen(") || preview.contains("socket_create(")
                    || preview.contains("system.net.sockets.tcpclient") || preview.contains("invoke-tcpclient")) {
                return "检测到脚本语言反向 Shell，当前为自动模式，拒绝执行。";
            }
        }
        if (bases.contains("mkfifo") && (bases.contains("nc") || bases.contains("ncat")) && hasShell) {
            return "检测到命名管道反向 Shell，当前为自动模式，拒绝执行。";
        }
        if (bases.contains("telnet")) {
            return "检测到 Telnet 反向 Shell 风险，当前为自动模式，拒绝执行。";
        }
        return null;
    }

    private static String sensitiveFileMessage(Result analysis, String preview) {
        String fragment = matchingSensitivePathFragment(preview);
        if (fragment == null) {
            return null;
        }
        Set<String> bases = candidateBases(analysis);
        if (hasCandidate(bases, SENSITIVE_READ_EXECUTABLES)) {
            return "检测到敏感文件访问（" + fragment + "），当前为自动模式，拒绝执行。";
        }
        for (Candidate candidate : analysis.candidates()) {
            if (candidate.executableBase().equals("ln")
                    && candidate.args().stream().anyMatch(arg -> arg.toLowerCase(Locale.ROOT).matches("-[a-z]*s.*"))) {
                return "检测到符号链接指向敏感路径（" + fragment + "），当前为自动模式，拒绝执行。";
            }
        }
        if (hasCandidate(bases, SCRIPT_EXECUTABLES) && containsAny(preview, SCRIPT_FILE_ACCESS_PATTERNS)) {
            return "检测到脚本语言访问敏感文件（" + fragment + "），当前为自动模式，拒绝执行。";
        }
        if ((bases.contains("base64") || bases.contains("xxd") || bases.contains("hexdump") || bases.contains("od"))
                && preview.contains("|")) {
            return "检测到通过编码绕过访问敏感文件（" + fragment + "），当前为自动模式，拒绝执行。";
        }
        return null;
    }

    private static String networkDownloadMessage(Result analysis) {
        for (Candidate candidate : analysis.candidates()) {
            String base = candidate.executableBase();
            List<String> args = lowerArgs(candidate);
            if (base.equals("curl")) {
                // -o /dev/null, -o - and redirects to /dev/null discard output instead of downloading.
                boolean writesFile = false;
                for (int i = 0; i < args.size(); i++) {
                    String arg = args.get(i);
                    String following = i + 1 < args.size() ? args.get(i + 1) : "";
                    if (arg.equals("--remote-name") || arg.startsWith("--remote-name=")) {
                        writesFile = true;
                        break;
                    }
                    if (arg.equals("--output")) {
                        if (!isDiscardTarget(following)) {
                            writesFile = true;
                            break;
                        }
                        continue;
                    }
                    if (arg.startsWith("--output=")) {
                        if (!isDiscardTarget(arg.substring("--output=".length()))) {
                            writesFile = true;
                            break;
                        }
                        continue;
                    }
                    if (REDIRECT_TOKEN.matcher(arg).matches()) {
                        if (!isDiscardTarget(following)) {
                            writesFile = true;
                            break;
                        }
                        continue;
                    }
                    if (arg.startsWith("--")) {
                        continue;
                    }
                    java.util.regex.Matcher shortOutput = CURL_SHORT_OUTPUT.matcher(arg);
                    if (!shortOutput.matches()) {
                        continue;
                    }
                    String target = shortOutput.group(1);
                    if (target.isEmpty()) {
                        target = following;
                    }
                    if (!isDiscardTarget(target)) {
                        writesFile = true;
                        break;
                    }
                }
                if (writesFile) {
                    return "沙盒模式下不允许通过 curl 下载外部文件，请关闭沙盒或切换到确认模式。";
                }
            }
            if (Set.of("wget", "scp", "ftp", "sftp", "fetch", "aria2c", "axel").contains(base)) {
                return "沙盒模式下不允许通过 " + base + " 下载外部文件，请关闭沙盒或切换到确认模式。";
            }
            if (base.equals("rsync") && args.stream().anyMatch(arg -> arg.matches("-[a-z]*[ra].*"))) {
                return "沙盒模式下不允许通过 rsync 下载外部文件，请关闭沙盒或切换到确认模式。";
            }
        }
        return null;
    }

    private static boolean isDiscardTarget(String target) {
        return target.equals("/dev/null") || target.equals("-");
    }

    // rm -rf /usr (no trailing slash) deletes the same tree as /usr/; the fragment lists use
    // trailing slashes for substring matching, so bare roots need an explicit equality check.
    private static boolean isBareSystemDirectory(List<String> args) {
        for (String arg : args) {
            for (String directory : SYSTEM_DIRECTORIES) {
                if (arg.equals(directory.substring(0, directory.length() - 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBareHomeRoot(List<String> args) {
        return args.stream().anyMatch(arg ->
                arg.equals("/home") || arg.equals("/home/") || arg.equals("/users") || arg.equals("/users/"));
    }

    static String destructiveActionMessage(Result analysis, String preview) {
        String sensitiveFragment = matchingSensitivePathFragment(preview);
        String criticalPath = matchingFragment(preview, SYSTEM_CRITICAL_PATHS);
        String historyPath = matchingFragment(preview, HISTORY_PATHS);
        for (Candidate candidate : analysis.candidates()) {
            String base = candidate.executableBase();
            List<String> args = lowerArgs(candidate);
            String targets = String.join(" ", args);
            boolean recursive = args.stream().anyMatch(arg -> arg.matches("-[a-z]*r.*"));
            boolean force = args.stream().anyMatch(arg -> arg.matches("-[a-z]*f.*"));
            if (base.equals("rm")) {
                if (sensitiveFragment != null) {
                    return "检测到删除敏感文件（" + sensitiveFragment + "），该操作不可逆，已拦截。";
                }
                if (criticalPath != null) {
                    return "检测到删除系统关键文件（" + criticalPath + "），该操作不可逆，已拦截。";
                }
                if (recursive && force && (containsAny(targets, HOME_DIRECTORY_FRAGMENTS)
                        || MAC_HOME_ROOT.matcher(targets).find() || isBareHomeRoot(args))) {
                    return "检测到递归删除用户目录，该操作不可逆，已拦截。";
                }
                if ((recursive || force) && (containsAny(targets, SYSTEM_DIRECTORIES)
                        || isBareSystemDirectory(args))) {
                    return "检测到删除系统目录文件，该操作不可逆，已拦截。";
                }
            }
            if (base.equals("chmod") && args.stream().anyMatch(arg ->
                    Set.of("777", "a+rwx", "a+rw", "a=rwx").contains(arg))
                    && (sensitiveFragment != null || criticalPath != null)) {
                return "检测到修改敏感或系统文件权限为全局可读写，已拦截。";
            }
            if (base.equals("chmod") && args.stream().anyMatch(arg ->
                    Set.of("777", "a+rwx", "a+rw", "a=rwx").contains(arg))
                    && args.stream().anyMatch(DangerousCommandGuard::hasVariableExpansion)) {
                return "检测到全局可读写权限修改的目标含未展开变量，无法核实实际路径，已拦截。";
            }
            if (base.equals("chown") && (sensitiveFragment != null || criticalPath != null)) {
                return "检测到修改敏感或系统文件所有者，已拦截。";
            }
            if (base.equals("chown") && args.stream().anyMatch(DangerousCommandGuard::hasVariableExpansion)) {
                return "检测到修改文件所有者的目标含未展开变量，无法核实实际路径，已拦截。";
            }
            if (base.equals("dd") && args.stream().anyMatch(arg -> arg.startsWith("of=/dev/"))) {
                return "检测到磁盘覆写操作，该操作不可逆，已拦截。";
            }
            if (base.equals("dd") && args.stream().anyMatch(arg ->
                    arg.startsWith("of=") && hasVariableExpansion(arg))) {
                return "检测到磁盘覆写目标含未展开变量，无法核实实际设备，已拦截。";
            }
            if (base.equals("kill") && args.stream().anyMatch(arg -> arg.equals("-9") || arg.equals("-kill"))
                    && args.stream().anyMatch(arg -> arg.equals("1") || arg.equals("-1"))) {
                return "检测到终止关键系统进程，已拦截。";
            }
            if (base.equals("pkill") && args.stream().anyMatch(arg -> arg.matches("-[a-z]*9.*"))) {
                return "检测到批量强制终止进程，已拦截。";
            }
            if (base.equals("shred")) {
                return "检测到安全擦除文件操作，该操作不可逆，已拦截。";
            }
            if (base.equals("umount") && args.stream().anyMatch(arg ->
                    arg.equals("/") || arg.equals("~") || arg.startsWith("~/"))) {
                return "检测到卸载文件系统操作，已拦截。";
            }
            if (base.equals("diskutil") && args.contains("unmount")) {
                return "检测到卸载文件系统操作，已拦截。";
            }
            if (base.equals("init") && args.stream().anyMatch(arg -> arg.equals("0") || arg.equals("6"))) {
                return "检测到关机/重启操作，已拦截。";
            }
            if (base.equals("truncate") && historyPath != null) {
                return "检测到截断历史文件（" + historyPath + "），已拦截。";
            }
            if (args.stream().anyMatch(arg -> REDIRECT_TOKEN.matcher(arg).matches())) {
                if (criticalPath != null) {
                    return "检测到覆盖系统关键文件（" + criticalPath + "），已拦截。";
                }
                if (historyPath != null) {
                    return "检测到清空历史或敏感文件（" + historyPath + "），已拦截。";
                }
                if (sensitiveFragment != null && (base.equals(":") || targets.contains("/dev/null"))) {
                    return "检测到清空敏感文件内容（" + sensitiveFragment + "），已拦截。";
                }
            }
        }
        if (LEADING_REDIRECT.matcher(preview).find() && historyPath != null) {
            return "检测到清空历史文件（" + historyPath + "），已拦截。";
        }
        return null;
    }

    private static String scriptDestructiveMessage(Result analysis, String preview) {
        if (!hasCandidate(candidateBases(analysis), SCRIPT_EXECUTABLES)) {
            return null;
        }
        if (!containsAny(preview, SCRIPT_DESTRUCTIVE_APIS)) {
            return null;
        }
        String fragment = matchingFragment(preview, SENSITIVE_PATH_FRAGMENTS);
        if (fragment == null) {
            fragment = matchingFragment(preview, SYSTEM_DIRECTORIES);
        }
        if (fragment == null) {
            fragment = matchingFragment(preview, HOME_DIRECTORY_FRAGMENTS);
        }
        if (fragment == null && MAC_HOME_ROOT.matcher(preview).find()) {
            fragment = "/users/";
        }
        if (fragment != null) {
            return "检测到脚本语言对敏感路径执行破坏性操作（" + fragment + "），已拦截。";
        }
        if (preview.contains("shutil.rmtree")) {
            return "检测到脚本语言递归删除目录，已拦截。";
        }
        if (preview.contains("os.system") && RM_WORD.matcher(preview).find()) {
            return "检测到脚本语言调用删除命令，已拦截。";
        }
        return null;
    }

    private static String windowsDestructiveMessage(Result analysis, String preview) {
        String sensitiveFragment = matchingSensitivePathFragment(preview);
        for (Candidate candidate : analysis.candidates()) {
            String base = candidate.executableBase();
            List<String> args = lowerArgs(candidate);
            if ((base.equals("del") || base.equals("erase")) && sensitiveFragment != null) {
                return "检测到删除 Windows 敏感文件，已拦截。";
            }
            if ((base.equals("del") || base.equals("erase"))
                    && containsAnyArg(args, "/f", "/q", "/s", "/a") && WINDOWS_SYSTEM_PATH.matcher(preview).find()) {
                return "检测到删除 Windows 系统目录文件，已拦截。";
            }
            if ((base.equals("rmdir") || base.equals("rd")) && containsAnyArg(args, "/s", "-s")
                    && (sensitiveFragment != null || WINDOWS_SYSTEM_PATH.matcher(preview).find()
                    || WINDOWS_USER_PATH.matcher(preview).find())) {
                return "检测到递归删除 Windows 敏感目录，已拦截。";
            }
            if (base.equals("taskkill") && containsAnyArg(args, "/f", "-f")
                    && (containsAnyArg(args, "/im", "-im") || pidIsOne(args))) {
                return "检测到强制终止 Windows 进程，已拦截。";
            }
            if (base.equals("sc") && containsAnyArg(args, "delete", "config")) {
                return "检测到修改或删除 Windows 系统服务，已拦截。";
            }
            if (base.equals("reg") && args.contains("delete")) {
                return "检测到删除 Windows 注册表项，已拦截。";
            }
            if (base.equals("bcdedit")) {
                return "检测到修改 Windows 启动配置，已拦截。";
            }
            if (base.equals("vssadmin") && args.contains("delete")) {
                return "检测到删除 Windows 卷影副本，已拦截。";
            }
            if (base.equals("cipher") && args.stream().anyMatch(arg -> arg.equals("/w") || arg.startsWith("/w:"))) {
                return "检测到擦除 Windows 空闲空间，已拦截。";
            }
            if (base.equals("mountvol") && containsAnyArg(args, "/d", "/n")) {
                return "检测到卸载 Windows 卷，已拦截。";
            }
            if (base.equals("icacls") && containsAnyArg(args, "/grant", "/deny", "/remove", "/reset")) {
                if (WINDOWS_SYSTEM_PATH.matcher(preview).find()
                        || preview.contains("everyone") && preview.contains(":f")) {
                    return "检测到修改 Windows 系统或公共权限，已拦截。";
                }
            }
            if (base.equals("takeown") && containsAnyArg(args, "/r", "/d")
                    && WINDOWS_SYSTEM_PATH.matcher(preview).find()) {
                return "检测到夺取 Windows 系统目录所有权，已拦截。";
            }
            if (base.equals("fsutil") && containsAnyArg(args, "createnew", "truncate", "zero", "setzerodata")) {
                return "检测到 Windows 文件系统破坏性操作，已拦截。";
            }
            if (base.equals("sdelete")) {
                return "检测到 Windows 安全擦除操作，已拦截。";
            }
            if (base.equals("wmic") && containsAny(String.join(" ", args),
                    List.of("call terminate", "service delete", "process delete"))) {
                return "检测到通过 wmic 终止进程或删除服务，已拦截。";
            }
            if (base.equals("netsh") && containsAny(preview,
                    List.of("firewall set opmode disable", "advfirewall set allprofiles state off",
                            "set opmode mode=disable"))) {
                return "检测到禁用 Windows 防火墙，已拦截。";
            }
            if (base.equals("net") && !args.isEmpty() && args.get(0).equals("user") && args.contains("/add")) {
                return "检测到添加 Windows 用户，已拦截。";
            }
            if (base.equals("remove-item") && containsAnyArg(args, "-recurse", "-force", "-r", "-f")
                    && (sensitiveFragment != null || WINDOWS_SYSTEM_PATH.matcher(preview).find()
                    || WINDOWS_USER_PATH.matcher(preview).find())) {
                return "检测到 PowerShell 递归删除敏感路径，已拦截。";
            }
            if ((base.equals("clear-content") || base.equals("set-content")) && sensitiveFragment != null) {
                return "检测到 PowerShell 覆盖敏感文件，已拦截。";
            }
            if (base.equals("stop-process") && (args.contains("-force") || idIsOne(args))) {
                return "检测到 PowerShell 终止关键进程，已拦截。";
            }
            if (Set.of("format-volume", "clear-disk", "remove-partition", "stop-computer",
                    "restart-computer", "remove-itemproperty", "clear-item").contains(base)) {
                return "检测到 PowerShell 高风险操作（" + base + "），已拦截。";
            }
            if (base.equals("set-executionpolicy") && containsAnyArg(args, "bypass", "unrestricted")) {
                return "检测到降低 PowerShell 执行策略，已拦截。";
            }
            if (Set.of("powershell", "powershell.exe", "pwsh", "pwsh.exe").contains(base)
                    && containsAnyArg(args, "-enc", "-encodedcommand")) {
                return "检测到 PowerShell Base64 编码命令，已拦截。";
            }
        }
        return null;
    }

    private static boolean pidIsOne(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            if ((args.get(i).equals("/pid") || args.get(i).equals("-pid"))
                    && i + 1 < args.size() && args.get(i + 1).equals("1")) {
                return true;
            }
        }
        return false;
    }

    private static boolean idIsOne(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("-id") && i + 1 < args.size() && args.get(i + 1).equals("1")) {
                return true;
            }
        }
        return false;
    }

    private static String dynamicExecutionMessage(Result analysis) {
        return dynamicExecutionMessage(analysis, 0);
    }

    private static String dynamicExecutionMessage(Result analysis, int depth) {
        String pipe = pipeToShellMessage(analysis);
        if (pipe != null) {
            return pipe;
        }
        for (Candidate candidate : analysis.candidates()) {
            String script = null;
            String label = "";
            if (candidate.executableBase().equals("eval")) {
                script = String.join(" ", candidate.args());
                label = "eval 动态执行";
            } else if (candidate.executableBase().equals("env")) {
                List<String> args = candidate.args();
                for (int i = 0; i < args.size(); i++) {
                    if ((args.get(i).equals("-S") || args.get(i).equals("--split-string")) && i + 1 < args.size()) {
                        script = String.join(" ", args.subList(i + 1, args.size()));
                        label = "env -S 动态执行";
                        break;
                    }
                }
            }
            if (script == null) {
                continue;
            }
            Result nested = ShellCommandAnalysis.analyze(script);
            String nestedPreview = script.toLowerCase(Locale.ROOT);
            for (Candidate nestedCandidate : nested.candidates()) {
                String message = dangerousCommandMessage(nestedCandidate);
                if (message != null) {
                    return "检测到 " + label + "高危命令：" + message;
                }
            }
            String destructive = destructiveActionMessage(nested, nestedPreview);
            if (destructive != null) {
                return "检测到 " + label + "高危命令：" + destructive;
            }
            String reverseShell = reverseShellMessage(nested, nestedPreview);
            if (reverseShell != null) {
                return "检测到 " + label + "高危命令：" + reverseShell;
            }
            String download = networkDownloadMessage(nested);
            if (download != null) {
                return "检测到 " + label + "高危命令：" + download;
            }
            if (depth < MAX_DYNAMIC_NESTING) {
                String deeper = dynamicExecutionMessage(nested, depth + 1);
                if (deeper != null) {
                    return "检测到 " + label + "高危命令：" + deeper;
                }
            } else {
                for (Candidate deeperCandidate : nested.candidates()) {
                    String base = deeperCandidate.executableBase();
                    if (base.equals("eval") || (base.equals("env") && deeperCandidate.args().contains("-S"))) {
                        return "检测到 " + label + "高危命令：动态执行嵌套过深，无法完整解析，当前为自动模式，拒绝执行。";
                    }
                }
            }
        }
        return null;
    }

    private static String pipeToShellMessage(Result analysis) {
        List<Candidate> candidates = analysis.candidates();
        if (candidates.isEmpty()) {
            return null;
        }
        Candidate last = candidates.get(candidates.size() - 1);
        if (!SHELL_EXECUTABLES.contains(last.executableBase())) {
            return null;
        }
        boolean dynamicUpstream = candidates.subList(0, candidates.size() - 1).stream()
                .anyMatch(candidate -> DYNAMIC_SCRIPT_SOURCES.contains(candidate.executableBase()));
        return dynamicUpstream ? "检测到通过管道向 Shell 提供动态脚本，当前为自动模式，拒绝执行。" : null;
    }

    private static String shellScriptPayload(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-c") || (arg.matches("-[a-zA-Z]+") && arg.toLowerCase(Locale.ROOT).contains("c"))) {
                return i + 1 < args.size() ? args.get(i + 1) : null;
            }
        }
        return null;
    }

    /** When nesting exceeds the analysis budget, peel shell -c payloads layer by layer (bounded). */
    private static String deepNestedScriptMessage(Result analysis) {
        if (!analysis.reasons().contains("max-depth")) {
            return null;
        }
        String script = null;
        for (Candidate candidate : analysis.candidates()) {
            if (!SHELL_EXECUTABLES.contains(candidate.executableBase())) {
                continue;
            }
            String payload = shellScriptPayload(candidate.args());
            if (payload != null) {
                script = payload;
            }
        }
        for (int depth = 0; script != null && depth < 32; depth++) {
            Result nested = ShellCommandAnalysis.analyze(script);
            String nestedPreview = script.toLowerCase(Locale.ROOT);
            for (Candidate candidate : nested.candidates()) {
                String message = dangerousCommandMessage(candidate);
                if (message != null) {
                    return "检测到深层嵌套高危命令：" + message;
                }
            }
            String destructive = destructiveActionMessage(nested, nestedPreview);
            if (destructive != null) {
                return "检测到深层嵌套高危命令：" + destructive;
            }
            String reverseShell = reverseShellMessage(nested, nestedPreview);
            if (reverseShell != null) {
                return "检测到深层嵌套高危命令：" + reverseShell;
            }
            String download = networkDownloadMessage(nested);
            if (download != null) {
                return "检测到深层嵌套高危命令：" + download;
            }
            String next = null;
            for (Candidate candidate : nested.candidates()) {
                if (!SHELL_EXECUTABLES.contains(candidate.executableBase())) {
                    continue;
                }
                String payload = shellScriptPayload(candidate.args());
                if (payload != null) {
                    next = payload;
                }
            }
            if (next == null || next.equals(script)) {
                break;
            }
            script = next;
        }
        return null;
    }

    private static List<String> lowerArgs(Candidate candidate) {
        List<String> args = new ArrayList<>(candidate.args().size());
        for (String arg : candidate.args()) {
            args.add(arg.toLowerCase(Locale.ROOT));
        }
        return args;
    }

    private static Set<String> candidateBases(Result analysis) {
        Set<String> bases = new java.util.HashSet<>();
        for (Candidate candidate : analysis.candidates()) {
            bases.add(candidate.executableBase());
        }
        return bases;
    }

    private static boolean hasCandidate(Set<String> bases, Set<String> executables) {
        return bases.stream().anyMatch(executables::contains);
    }

    private static boolean hasVariableExpansion(String text) {
        return VARIABLE_EXPANSION.matcher(text).find();
    }

    private static boolean containsAny(String text, List<String> fragments) {
        return fragments.stream().anyMatch(text::contains);
    }

    private static boolean containsAnyArg(List<String> args, String... values) {
        for (String value : values) {
            if (args.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static String matchingFragment(String text, List<String> fragments) {
        return fragments.stream().filter(text::contains).findFirst().orElse(null);
    }

    private static String matchingSensitivePathFragment(String text) {
        String fragment = matchingFragment(text, SENSITIVE_PATH_FRAGMENTS);
        if (fragment != null) {
            return fragment;
        }
        return ENV_FILE.matcher(text).find() ? ".env" : null;
    }
}