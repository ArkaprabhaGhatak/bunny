package org.rabix.engine.service.cache.generic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rabix.engine.dao.Repository;
import org.rabix.engine.service.cache.generic.CacheItem.Action;

public class Cache<C extends Cachable, R extends Repository<C>> {

  private R repository;
  private Class<C> entityClass;
  
  private Map<CacheKey, CacheItem<C>> cache = new HashMap<>();
  
  public Cache(R repository, Class<C> entityClass) {
    this.repository = repository;
    this.entityClass = entityClass;
  }
  
  public void clear() {
    cache.clear();
  }
  
  public void flush() {
    Collection<CacheItem<C>> items = getCacheItems();
    
    List<CacheItem<C>> inserts = new ArrayList<>();
    List<CacheItem<C>> updates = new ArrayList<>();
    
    for (CacheItem<C> item : items) {
      switch (item.action) {
      case INSERT:
        inserts.add(item);
        break;
      case UPDATE:
        updates.add(item);
        break;
      default:
        break;
      }
    }
    
    try {
      Method insertMethod = repository.getClass().getMethod("insert", entityClass);
      for (CacheItem<C> item : inserts) {
        insertMethod.invoke(repository, item.cachable);
      }

      Method updateMethod = repository.getClass().getMethod("update", entityClass);
      for (CacheItem<C> item : updates) {
        updateMethod.invoke(repository, item.cachable);
      }
    } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
    }
    cache.clear();
  }
  
  public void put(C cachable) {
    put(cachable, Action.UPDATE);
  }
  
  public void put(Collection<C> cachables) {
    for (C cachable : cachables) {
      put(cachable, Action.UPDATE);
    }
  }
  
  public void put(C cachable, Action action) {
    CacheKey key = cachable.generateKey();
    if (cache.containsKey(key)) {
      CacheItem<C> item = cache.get(key);
      item.cachable = cachable;
    } else {
      cache.put(key, new CacheItem<C>(action, cachable));
    }
  }
  
  public Cachable get(CacheKey key) {
    return cache.get(key) != null ? cache.get(key).cachable : null;
  }
  
  public boolean isEmpty() {
    return cache.isEmpty();
  }
  
  public Set<Cachable> get() {
    Set<Cachable> records = new HashSet<>();
    for (CacheItem<C> item : cache.values()) {
      records.add(item.cachable);
    }
    return records;
  }
  
  public Collection<CacheItem<C>> getCacheItems() {
    return cache.values();
  }
  
}