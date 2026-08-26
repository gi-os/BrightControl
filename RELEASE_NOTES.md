## BrightControl v3.72 — a failed relayed command files its own transcript

BrightOura's pairing helper printed `removeBond false`, `createBond false`, `state NONE` — and none
of it reached anybody, because the *command* succeeded. It exited zero having printed the word
FAILED, so nothing in here counted it as a failure, and the transcript was **hand-typed into a chat
window, line by line**, to get it looked at.

That is the wrong way round twice over. This app holds a reporting key; the apps it relays for mostly
do not — BrightOura's own reports have been queuing on the phone unsent since the day it was written.
So the one place a relayed command's own words exist was the one place nothing could read them.

**A run that did not work now files its transcript, once.** Failure is read from the outcomes *and*
from what the command said — `FAILED`, `refused`, `gave up` — because a command can fail perfectly
politely with an exit code of zero. The tail of the transcript goes in the report, which is worth far
more than the outcome codes around it: `createBond true` followed by `no request ever arrived` is a
diagnosis, and `FAILED` is not.

One report per run, since [Trouble] holds a floor of a minute between any two whatever they say.
