## BrightControl v4.43 -- SEND LOG files once per log, not once per tap

**Tapping SEND LOG three times on the Wi-Fi login screen filed three identical reports.** light-reports#673, #674 and #675 are the same log: the screen opened with no Wi-Fi network connected, said so, and the user tapped SEND LOG at 3.5s, 5.9s and 7.3s. Each tap posted a full report, and the only difference between them is the extra "SEND LOG tapped" lines at the end.

The button was written to always file, since it is the user asking, and nothing checked whether the previous tap had already filed the same thing. On the Light Phone a tap often gives no visible feedback until the send comes back, so tapping again is the natural thing to do.

The log now counts the lines that say something about the network, and leaves out the lines SEND LOG writes about itself. A tap files only when that count has moved since the last tap that filed. Otherwise the status line reads "Already sent — nothing new in the log since." and nothing is posted. Anything new that happens on the screen, such as a page loading or a probe failing, makes the next tap file again.

Fixes [light-reports#673] -- one Wi-Fi login log arrived as three reports after SEND LOG was tapped three times.
