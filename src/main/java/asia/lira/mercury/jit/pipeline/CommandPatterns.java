package asia.lira.mercury.jit.pipeline;

import java.util.regex.Pattern;

public final class CommandPatterns {
    private CommandPatterns() {
    }

    public static final Pattern SCOREBOARD_SET = Pattern.compile("^scoreboard\\s+players\\s+set\\s+(\\S+)\\s+(\\S+)\\s+(-?\\d+)$");
    public static final Pattern SCOREBOARD_ADD = Pattern.compile("^scoreboard\\s+players\\s+add\\s+(\\S+)\\s+(\\S+)\\s+(-?\\d+)$");
    public static final Pattern SCOREBOARD_REMOVE = Pattern.compile("^scoreboard\\s+players\\s+remove\\s+(\\S+)\\s+(\\S+)\\s+(-?\\d+)$");
    public static final Pattern SCOREBOARD_GET = Pattern.compile("^scoreboard\\s+players\\s+get\\s+(\\S+)\\s+(\\S+)$");
    public static final Pattern SCOREBOARD_RESET = Pattern.compile("^scoreboard\\s+players\\s+reset\\s+(\\S+)\\s+(\\S+)$");
    public static final Pattern SCOREBOARD_OPERATION = Pattern.compile("^scoreboard\\s+players\\s+operation\\s+(\\S+)\\s+(\\S+)\\s+(=|\\+=|-=|\\*=|/=|%=|<|>|><)\\s+(\\S+)\\s+(\\S+)$");

    public static final Pattern DATA_MODIFY_STORAGE_SET_VALUE = Pattern.compile("^data\\s+modify\\s+storage\\s+(\\S+)\\s+(\\S+)\\s+set\\s+value\\s+(.+)$");
    public static final Pattern DATA_MODIFY_STORAGE_SET_FROM = Pattern.compile("^data\\s+modify\\s+storage\\s+(\\S+)\\s+(\\S+)\\s+set\\s+from\\s+storage\\s+(\\S+)\\s+(\\S+)$");
    public static final Pattern DATA_MODIFY_STORAGE_MERGE_VALUE = Pattern.compile("^data\\s+modify\\s+storage\\s+(\\S+)\\s+(\\S+)\\s+merge\\s+value\\s+(.+)$");
    public static final Pattern DATA_MODIFY_STORAGE_MERGE_FROM = Pattern.compile("^data\\s+modify\\s+storage\\s+(\\S+)\\s+(\\S+)\\s+merge\\s+from\\s+storage\\s+(\\S+)\\s+(\\S+)$");
}
