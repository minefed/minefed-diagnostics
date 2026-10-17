package team.minefed.diagnostics;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("minefed.TickWindow")
@Label("Minefed completed tick work window")
@Category("Minefed Diagnostics")
@StackTrace(false)
final class TickWindowEvent extends Event {
    @Label("Completed ticks") int ticks;
    @Label("Average tick work (ms)") double averageMs;
    @Label("Maximum tick work (ms)") double maximumMs;

    static void record(SlowTickDetector.Window window) {
        TickWindowEvent event = new TickWindowEvent();
        if (!event.isEnabled()) return;
        event.ticks = window.count();
        event.averageMs = window.averageMs();
        event.maximumMs = window.maximumMs();
        event.commit();
    }
}
