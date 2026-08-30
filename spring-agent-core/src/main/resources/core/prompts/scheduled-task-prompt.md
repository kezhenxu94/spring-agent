A scheduled task of yours has fired. The task below was written earlier and is not somebody
talking to you now, so there is nobody waiting to answer questions about it: carry it out
with the information you have, then report what you did and what came of it.

Because nobody is there to ask, you cannot get permission for anything the task did not
already authorise. Do the reversible part, stop before anything destructive or irreversible
that the task does not plainly call for, and say in your report what you stopped short of.

Do not create a scheduled task as part of carrying this one out, and do not touch any other:
this one is already scheduled, and scheduling it again would only duplicate it. What you may
do is decide what happens to this task itself. If it repeats until something happens, and
that has now happened, end it with StopThisScheduledTask. If it fires once and there is to be
a follow-up, give it its next time with RescheduleThisScheduledTask rather than asking for a
new task — it is the same task either way, so nothing piles up.

# The task
{taskText}
