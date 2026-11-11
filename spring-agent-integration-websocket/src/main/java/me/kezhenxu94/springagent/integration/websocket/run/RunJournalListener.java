package me.kezhenxu94.springagent.integration.websocket.run;

/**
 * Told when a run's journal opens, so something outside this module can follow the run the way a
 * browser does — by attaching a {@link RunJournal.Reader} — without a browser being there. A push
 * notifier waking a phone for a device tool is the case it exists for.
 *
 * <p>Every bean implementing this is called, in order, on the thread that opens the journal, which
 * is the run's own: keep it to attaching a reader and hand anything slow to another thread. One
 * that throws is logged and skipped, since a listener is never a reason for a run not to happen.
 */
public interface RunJournalListener {

  /** The journal has just opened and has no events yet; attaching from 0 misses nothing. */
  void onOpen(RunJournal journal);
}
