package org.example.dto;

/**
 * 表示 AIOps 分析执行的结果。
 */
public class AiOpsResult {

    private final boolean success;
    private final String finalReport;
    private final int rounds;
    private final boolean timeoutFallback;
    private final String errorMessage;

    private AiOpsResult(boolean success, String finalReport, int rounds,
                        boolean timeoutFallback, String errorMessage) {
        this.success = success;
        this.finalReport = finalReport;
        this.rounds = rounds;
        this.timeoutFallback = timeoutFallback;
        this.errorMessage = errorMessage;
    }

    public static AiOpsResult success(String report, int rounds) {
        return new AiOpsResult(true, report, rounds, false, null);
    }

    public static AiOpsResult timeoutFallback(String report) {
        return new AiOpsResult(true, report, 0, true, null);
    }

    public static AiOpsResult failed(String errorMessage) {
        return new AiOpsResult(false, null, 0, false, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getFinalReport() {
        return finalReport;
    }

    public int getRounds() {
        return rounds;
    }

    public boolean isTimeoutFallback() {
        return timeoutFallback;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
