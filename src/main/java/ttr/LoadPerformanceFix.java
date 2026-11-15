package ttr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import ttr.events.XmlLoadEvents;

/** Compatibility patch for TTWR 1.4.2.1; compiled for Java 8. */
public final class LoadPerformanceFix {
    private LoadPerformanceFix() {}

    public static void updateLocations(DBPlayers db, boolean clear) {
        if (clear) {
            Player.countryList.clear();
            Player.regionList2.clear();
            Player.townList.clear();
        }
        Set<String> countries = new HashSet<String>(Player.countryList);
        Set<String> regions = new HashSet<String>(Player.regionList2);
        Set<String> towns = new HashSet<String>(Player.townList);
        for (Player player : db.players.values()) {
            add(Player.townList, towns, player.profile.Town);
            add(Player.countryList, countries, player.profile.Country);
            add(Player.regionList2, regions, player.profile.Region);
        }
        // Stable sorting preserves the original order of case variants.
        Collections.sort(Player.townList, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(Player.countryList, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(Player.regionList2, String.CASE_INSENSITIVE_ORDER);
    }

    private static void add(ArrayList<String> list, Set<String> seen, String value) {
        if (!value.isEmpty() && seen.add(value)) list.add(value);
    }

    public static XmlLoadEvents throttle(XmlLoadEvents callback) {
        return callback == null ? null : new Progress(callback);
    }

    private static final class Progress implements XmlLoadEvents {
        private final XmlLoadEvents target;
        private long bytesAt;
        private long elementsAt;
        private boolean bytesStarted;
        private boolean elementsStarted;
        private static final long INTERVAL_NS = 100000000L;

        Progress(XmlLoadEvents target) { this.target = target; }

        public void OnSectionStarted(String section) {
            bytesStarted = false;
            elementsStarted = false;
            target.OnSectionStarted(section);
        }

        public void OnBytesRead(long current, long total) {
            long now = System.nanoTime();
            if (!bytesStarted || (total > 0 && current >= total)
                    || now - bytesAt >= INTERVAL_NS) {
                bytesStarted = true;
                bytesAt = now;
                target.OnBytesRead(current, total);
            }
        }

        public void OnElementParsed(long current, long total) {
            long now = System.nanoTime();
            if (!elementsStarted || (total > 0 && current >= total)
                    || now - elementsAt >= INTERVAL_NS) {
                elementsStarted = true;
                elementsAt = now;
                target.OnElementParsed(current, total);
            }
        }

        public void SetErrorCode(int code, String message) {
            target.SetErrorCode(code, message);
        }
    }
}
