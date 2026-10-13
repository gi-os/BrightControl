## BrightControl v4.46 -- a pairing the phone accepts now connects

**The phone accepted the pairing, and then the connect failed in the same second**. This happened after START OVER AND PAIR, after FORGET THE PAIRING, and after the automatic recovery from a refused key. The trail said "mDNS found nothing", and the message blamed the wireless debugging switch. In light-reports#700 the switch was on. Each new try failed the same way until the app process ended.

The fault was in libadb, the library that talks to the phone's debugging service. It makes one TLS context from the first key it gets. After that, it gives every connection the same context, whatever key the connection has. The ADB screen connects with the stored key when it opens. START OVER AND PAIR then makes a new key, and the phone pairs with it. But each connect still presented the certificate of the old key, so the phone refused it.

The app now clears that TLS context when the key changes. Each connection presents the key that the phone paired. A unit test reads the libadb field that this fix uses. If a libadb update moves that field, the test fails in CI.

Other changes in this release:

After a pairing, the app tries to connect three times in about five seconds. The phone writes a new key to its key file a moment after the pairing. A connect that arrives before that write can fail once.

The LAST PAIRING ATTEMPT list shows what came back from each try. Before, it showed "mDNS found nothing" for every failure.

The failure message says what the tries found. It mentions the wireless debugging switch only when the switch is off.

A report of this failure includes the pairing trail. Before, every report had the same fixed sentence and no trail.

The "Reported: ..." line at the bottom of the screen goes away after ten seconds again. A fault in its timer kept it on screen until the app closed.

"Send my settings to the developer" sends once for each visit to the screen. More taps sent the same settings again.

Reports name the screen that they come from. Before, every report said `buttons`.

**If pairing failed on v4.45**: install v4.46 and open ADB & grants. If the screen says Connected, tap GRANT ALL. If it does not, tap START OVER AND PAIR.

Fixes light-reports#700. Reports #682, #699 and #708 show the same failure on other phones.
