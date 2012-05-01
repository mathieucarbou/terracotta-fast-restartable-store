/*
 * All content copyright (c) 2012 Terracotta, Inc., except as may otherwise
 * be noted in a separate copyright notice. All rights reserved.
 */
package com.terracottatech.frs.object;

import java.util.Set;

/**
 * @author tim
 */
public class NullObjectManager<I, K, V> implements ObjectManager<I, K, V> {
  @Override
  public long getLowestLsn() {
    return 0;
  }

  @Override
  public long getLsn(I id, K key) {
    return 0;
  }

  @Override
  public void put(I id, K key, V value, long lsn) {
  }

  @Override
  public void delete(I id) {
  }

  @Override
  public void remove(I id, K key) {
  }

  @Override
  public Set<Long> replayPut(I id, K key, V value, long lsn) {
    return null;
  }

  @Override
  public ObjectManagerEntry<I, K, V> acquireCompactionEntry() {
    return null;
  }

  @Override
  public void releaseCompactionEntry(ObjectManagerEntry<I, K, V> ikvObjectManagerEntry) {
  }

  @Override
  public void updateLsn(ObjectManagerEntry<I, K, V> entry, long newLsn) {
  }

  @Override
  public long size() {
    return 0;
  }

  @Override
  public void updateLowestLsn() {
  }
}
