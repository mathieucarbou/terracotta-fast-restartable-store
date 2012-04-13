/*
 * All content copyright (c) 2012 Terracotta, Inc., except as may otherwise
 * be noted in a separate copyright notice. All rights reserved.
 */
package com.terracottatech.frs;

import com.terracottatech.frs.action.Action;
import com.terracottatech.frs.action.ActionCodec;
import com.terracottatech.frs.object.ObjectManager;
import com.terracottatech.frs.transaction.TransactionLockProvider;
import com.terracottatech.frs.util.ByteBufferUtils;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.locks.Lock;

/**
 * @author tim
 */
class DeleteAction implements Action {
  private final ObjectManager<ByteBuffer, ?, ?> objectManager;
  private final ByteBuffer id;

  DeleteAction(ObjectManager<ByteBuffer, ?, ?> objectManager, ByteBuffer id) {
    this.objectManager = objectManager;
    this.id = id;
  }

  DeleteAction(ObjectManager<ByteBuffer, ?, ?> objectManager, ActionCodec codec, ByteBuffer[] buffers) {
    this.objectManager = objectManager;
    int idLength = ByteBufferUtils.getInt(buffers);
    this.id = ByteBufferUtils.getBytes(idLength, buffers);
  }

  ByteBuffer getId() {
    return id;
  }

  @Override
  public long getPreviousLsn() {
    return 0;
  }

  @Override
  public void record(long lsn) {
    objectManager.delete(id);
  }

  @Override
  public void replay(long lsn) {
    // nothing to do on replay
  }

  @Override
  public Collection<Lock> lock(TransactionLockProvider lockProvider) {
    Lock lock = lockProvider.getLockForId(id).writeLock();
    lock.lock();
    return Collections.singleton(lock);
  }

  @Override
  public ByteBuffer[] getPayload(ActionCodec codec) {
    ByteBuffer buffer = ByteBuffer.allocate(ByteBufferUtils.INT_SIZE);
    buffer.putInt(id.remaining()).flip();
    return new ByteBuffer[] { buffer, id.duplicate() };
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    DeleteAction that = (DeleteAction) o;

    return !(id != null ? !id.equals(that.id) : that.id != null);
  }

  @Override
  public int hashCode() {
    return id != null ? id.hashCode() : 0;
  }
}
