/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.terracottatech.fastrestartablestore.mock;

import com.terracottatech.fastrestartablestore.Transaction;
import com.terracottatech.fastrestartablestore.TransactionHandle;
import com.terracottatech.fastrestartablestore.TransactionManager;
import com.terracottatech.fastrestartablestore.spi.ObjectManager;

/**
 *
 * @author cdennis
 */
class MockTransaction implements Transaction<Long, String, String> {

  private final TransactionManager txnManager;
  private final ObjectManager<Long, String, String> objManager;
  private final TransactionHandle txnHandle;
  
  public MockTransaction(TransactionManager txnManager, ObjectManager<Long, String, String> objManager) {
    this.txnManager = txnManager;
    this.objManager = objManager;
    this.txnHandle = txnManager.begin();
  }

  @Override
  public Transaction<Long, String, String> put(Long id, String key, String value) {
    txnManager.happened(txnHandle, new MockPutAction<Long, String, String>(objManager, id, key, value));
    return this;
  }

  @Override
  public Transaction<Long, String, String> remove(Long id, String key) {
    txnManager.happened(txnHandle, new MockRemoveAction<Long, String>(objManager, id, key));
    return this;
  }

  @Override
  public Transaction<Long, String, String> delete(Long id) {
    txnManager.happened(txnHandle, new MockDeleteAction<Long>(objManager, id));
    return this;
  }

  @Override
  public void commit() {
    txnManager.commit(txnHandle);
  }
  
}
