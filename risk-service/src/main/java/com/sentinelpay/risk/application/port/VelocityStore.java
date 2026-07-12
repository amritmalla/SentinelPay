package com.sentinelpay.risk.application.port;

import com.sentinelpay.risk.application.model.VelocityReadResult;
import com.sentinelpay.risk.application.model.VelocityWindow;

/**
 * Ephemeral velocity counters for fraud scoring. Backed by Redis; loss degrades scoring (never money state).
 */
public interface VelocityStore {

    long increment(String email, VelocityWindow window);

    VelocityReadResult getCount(String email, VelocityWindow window);
}
