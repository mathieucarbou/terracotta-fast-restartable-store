/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.terracottatech.fastrestartablestore.messages;

/**
 *
 * @author cdennis
 */
public interface KeyedAction<K> extends Action {
  
  K getKey();
  
}
