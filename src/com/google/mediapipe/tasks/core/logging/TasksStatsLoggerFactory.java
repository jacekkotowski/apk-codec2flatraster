package com.google.mediapipe.tasks.core.logging;

import android.content.Context;

/** Replaces MediaPipe's own class of this name (removed from the jar by third_party/mediapipe/get.sh). MediaPipe's version sends usage
 *  statistics to Google through the Android data-transport library; FlatRaster does not include that library and sends nothing: this
 *  returns the logger that does nothing. */
public final class TasksStatsLoggerFactory {
    public static TasksStatsLogger create(Context context, String solutionName, String solutionMode) {
        return TasksStatsDummyLogger.create(context, solutionName, solutionMode);
    }
}
