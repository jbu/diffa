package net.lshift.diffa.participant.common;

import net.lshift.diffa.participant.scanning.ScanResultEntry;

/**
 * Created with IntelliJ IDEA.
 * User: ceri
 * Date: 12/06/19
 * Time: 20:51
 * To change this template use File | Settings | File Templates.
 */
public interface ScanResultEntryProcessor {
    void process(ScanResultEntry entry);
}
