/*
 * Copyright (c) 1998-2011 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.memcache;

import com.caucho.server.distcache.AbstractCacheEngine;
import com.caucho.server.distcache.CacheConfig;
import com.caucho.server.distcache.CacheStoreManager;
import com.caucho.server.distcache.DistCacheEntry;
import com.caucho.server.distcache.MnodeEntry;
import com.caucho.server.distcache.MnodeUpdate;
import com.caucho.server.distcache.MnodeValue;
import com.caucho.util.Alarm;
import com.caucho.util.CharBuffer;
import com.caucho.util.Base64;
import com.caucho.util.HashKey;

/**
 * Custom serialization for the cache
 */
public class MemcacheCacheEngine extends AbstractCacheEngine
{
  private CacheStoreManager _cacheService;
  private MemcacheClient _client;
  
  MemcacheCacheEngine(CacheStoreManager cacheService,
                      MemcacheClient client)
  {
    _cacheService = cacheService;
    _client = client;
  }
  

  @Override
  public boolean isLocalExpired(CacheConfig config,
                                  HashKey key,
                                  MnodeEntry mnodeEntry,
                                  long now)
  {
    return mnodeEntry.isLocalExpired(now, config);
  }

  @Override
  public MnodeEntry get(DistCacheEntry entry, CacheConfig config)
  {
    CharBuffer cb = new CharBuffer();
    
    Base64.encode(cb, entry.getKeyHash().getHash());
    
    String key = cb.toString();
    
    MnodeUpdate update = _client.getResinIfModified(key, 
                                                    entry.getValueHashKey(),
                                                    entry,
                                                    config);
    
    if (update != null)
      return _cacheService.putLocalValue(entry, update, null, 0, 0);
    else
      return entry.getMnodeEntry();
  }

  @Override
  public void put(HashKey hashKey, 
                  MnodeUpdate mnodeUpdate,
                  MnodeEntry mnodeValue)
  {
    CharBuffer cb = new CharBuffer();
    
    Base64.encode(cb, hashKey.getHash());
    
    String key = cb.toString();
    
    _client.putResin(key, mnodeUpdate);
  }
  
  @Override
  public void remove(HashKey hashKey, 
                     MnodeUpdate mnodeUpdate,
                     MnodeEntry mnodeEntry)
  {
    CharBuffer cb = new CharBuffer();
    
    Base64.encode(cb, hashKey.getHash());
    
    String key = cb.toString();
    
    _client.removeImpl(key);
  }
}
