/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.terracottatech.fastrestartablestore.mock;

import com.terracottatech.fastrestartablestore.ReplayFilter;
import com.terracottatech.fastrestartablestore.spi.ObjectManager;

import java.io.Serializable;

/**
 *
 * @author cdennis
 */
class MockRemoveAction<I, K> extends MockCompleteKeyAction<I, K> implements Serializable, MockAction {

  private transient ObjectManager<I, K, ?> objManager;
  
  public MockRemoveAction(ObjectManager<I, K, ?> objManager, I id, K key) {
    super(id, key);
    this.objManager = objManager;
  }

  @Override
  public long getLsn() {
    return objManager.getLsn(getId(), getKey());
  }

  @Override
  public void record(long lsn) {
     objManager.remove(getId(), getKey());
  }

  @Override
  public boolean replay(ReplayFilter filter, long lsn) {
    if (!filter.disallows(this)) {
      return true;
    } else {
      return false;
    }
  }
  
  public String toString() {
    return "Action: remove(" + getId() + ":" + getKey() + ")";
  }

  @Override
  public void setObjectManager(ObjectManager<?, ?, ?> objManager) {
    this.objManager = (ObjectManager<I, K, ?>) objManager;
  }
}
