/*
 * Copyright (c) 1998-2008 Caucho Technology -- all rights reserved
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

package com.caucho.server.cluster;

import com.caucho.config.ConfigException;
import com.caucho.db.jdbc.DataSourceImpl;
import com.caucho.util.Alarm;
import com.caucho.util.AlarmListener;
import com.caucho.util.FreeList;
import com.caucho.util.L10N;
import com.caucho.vfs.Path;
import com.caucho.vfs.ReadStream;
import com.caucho.vfs.WriteStream;
import com.caucho.server.admin.Management;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Manages backing for the cache map.
 */
class CacheMapBacking implements AlarmListener {
  private static final L10N L = new L10N(CacheMapBacking.class);
  private static final Logger log
    = Logger.getLogger(CacheMapBacking.class.getName());
  
  private FreeList<CacheMapConnection> _freeConn
    = new FreeList<CacheMapConnection>(32);

  private final Path _path;
  private final String _tableName;

  private DataSource _dataSource;
    
  private String _loadQuery;
  
  private String _insertQuery;
  private String _updateSaveQuery;
  private String _updateAccessQuery;
  
  private String _removeQuery;
  
  private String _updateValidQuery;
  private String _updateVersionQuery;
  
  private String _timeoutQuery;
  
  private String _countQuery;

  private long _version;

  private Alarm _alarm;
  
  CacheMapBacking(Path path, String serverName)
    throws Exception
  {
    _path = path;
    _tableName = serverNameToTableName(serverName);

    if (_path == null)
      throw new NullPointerException();
    
    if (_tableName == null)
      throw new NullPointerException();

    init();
  }

  private void init()
    throws Exception
  {
    _loadQuery = ("SELECT value,access_time,version"
		  + " FROM " + _tableName
		  + " WHERE id=? AND is_valid=1 AND is_dead=0");

    _insertQuery = ("INSERT into " + _tableName
		    + " (id,value,is_valid,is_dead,access_time,timeout,"
		    + "  version) "
		    + "VALUES(?,?,1,0,?,?,?)");

    _updateSaveQuery
      = ("UPDATE " + _tableName
	 + " SET value=?,access_time=?,is_valid=1,is_dead=0,version=?"
	 + " WHERE id=?");

    _updateAccessQuery
      = ("UPDATE " + _tableName
	 + " SET access_time=?,version=?"
	 + " WHERE id=?");

    _removeQuery = ("UPDATE " + _tableName
		    + " SET is_dead=1"
		    + " WHERE id=?");

    _updateValidQuery = ("UPDATE " + _tableName
			 + " SET is_valid=0"
			 + " WHERE id=? AND value <> ?");

    _updateVersionQuery = ("UPDATE " + _tableName
			   + " SET access_time=?, version=?"
			   + " WHERE id=? AND value=?");

    _timeoutQuery = ("DELETE FROM " + _tableName
		     + " WHERE access_time + 5 * timeout / 4 < ?");
    
    _countQuery = "SELECT count(*) FROM " + _tableName;
    
    try {
      _path.mkdirs();
    } catch (IOException e) {
    }

    DataSourceImpl dataSource = new DataSourceImpl();
    dataSource.setPath(_path);
    dataSource.setRemoveOnError(true);
    dataSource.init();
    
    _dataSource = dataSource;

    initDatabase();

    _version = initVersion();

    // _alarm = new Alarm(this);
    // _alarm.queue(0);
  }

  /**
   * Returns the data source.
   */
  public DataSource getDataSource()
  {
    return _dataSource;
  }

  /**
   * Create the database, initializing if necessary.
   */
  private void initDatabase()
    throws Exception
  {
    Connection conn = _dataSource.getConnection();

    try {
      Statement stmt = conn.createStatement();
      
      try {
	String sql = ("SELECT id, value, timeout, access_time,"
		      + "     version, is_dead, is_valid"
                      + " FROM " + _tableName + " WHERE 1=0");

	ResultSet rs = stmt.executeQuery(sql);
	rs.next();
	rs.close();

	return;
      } catch (Exception e) {
	log.log(Level.FINEST, e.toString(), e);
	log.finer(this + " " + e.toString());
      }

      try {
	stmt.executeQuery("DROP TABLE " + _tableName);
      } catch (Exception e) {
	log.log(Level.FINEST, e.toString(), e);
      }

      String sql = ("CREATE TABLE " + _tableName + " (\n"
                    + "  id BINARY(20) PRIMARY KEY,\n"
                    + "  value BINARY(20),\n"
		    + "  timeout BIGINT,\n"
		    + "  access_time BIGINT,\n"
		    + "  version INTEGER,\n"
		    + "  is_dead BIT,\n"
		    + "  is_valid BIT)");


      log.fine(sql);

      stmt.executeUpdate(sql);
    } finally {
      conn.close();
    }
  }

  /**
   * Returns the version
   */
  private int initVersion()
    throws Exception
  {
    Connection conn = _dataSource.getConnection();

    try {
      Statement stmt = conn.createStatement();
      
      String sql = ("SELECT MAX(version)"
		    + " FROM " + _tableName);

      ResultSet rs = stmt.executeQuery(sql);
      if (rs.next())
	return rs.getInt(1) + 1;
    } finally {
      conn.close();
    }

    return 1;
  }

  /**
   * Reads the object from the data store.
   *
   * @param id the hash identifier for the data
   * @param os the WriteStream to hold the data
   *
   * @return true on successful load
   */
  public HashKey load(HashKey id)
  {
    CacheMapConnection conn = null;
    
    try {
      conn = getConnection();

      PreparedStatement pstmt = conn.prepareLoad();
      pstmt.setBytes(1, id.getHash());

      ResultSet rs = pstmt.executeQuery();
      
      if (rs.next()) {
	byte []hash = rs.getBytes(1);
	long accessTime = rs.getLong(2);
	long version = rs.getLong(3);

	return new HashKey(hash);
      }
    } catch (SQLException e) {
      log.log(Level.FINE, e.toString(), e);
    } finally {
      if (conn != null)
	conn.close();
    }

    return null;
  }
  
  /**
   * Stores the data, returning true on success
   *
   * @param id the key hash
   * @param value the value hash
   * @param timeout the item's timeout
   */
  public boolean insert(HashKey id, HashKey value, long timeout)
  {
    CacheMapConnection conn = null;

    try {
      conn = getConnection();

      PreparedStatement stmt = conn.prepareInsert();
      stmt.setBytes(1, id.getHash());
      stmt.setBytes(2, value.getHash());
      stmt.setLong(3, Alarm.getCurrentTime());
      stmt.setLong(4, timeout);
      stmt.setLong(5, _version);

      int count = stmt.executeUpdate();
        
      if (log.isLoggable(Level.FINER)) 
	log.finer(this + " insert key=" + id + " value=" + value);
	  
      return true;
    } catch (SQLException e) {
      log.log(Level.FINER, e.toString(), e);
    } finally {
      if (conn != null)
	conn.close();
    }

    return false;
  }
  
  /**
   * Stores the data, returning true on success
   *
   * @param id the key hash
   * @param value the value hash
   * @param timeout the item's timeout
   */
  public boolean updateSave(HashKey id, HashKey value, long timeout)
  {
    CacheMapConnection conn = null;

    try {
      conn = getConnection();

      PreparedStatement stmt = conn.prepareUpdateSave();
      stmt.setBytes(1, value.getHash());
      stmt.setLong(2, Alarm.getCurrentTime());
      stmt.setLong(3, _version);
      stmt.setBytes(4, id.getHash());

      int count = stmt.executeUpdate();
        
      if (log.isLoggable(Level.FINER)) 
	log.finer(this + " updateSave key=" + id + " value=" + value);
	  
      return count > 0;
    } catch (SQLException e) {
      log.log(Level.FINER, e.toString(), e);
    } finally {
      if (conn != null)
	conn.close();
    }

    return false;
  }

  /**
   * Clears the expired data
   */
  public void removeExpiredData()
  {
    CacheMapConnection conn = null;

    try {
      conn = getConnection();
      PreparedStatement pstmt = conn.prepareTimeout();
  
      long now = Alarm.getCurrentTime();
	
      pstmt.setLong(1, now);

      int count = pstmt.executeUpdate();

      if (count > 0)
	log.finer(this + " expired " + count + " old data");
    } catch (SQLException e) {
      log.log(Level.FINE, e.toString(), e);
    } finally {
      conn.close();
    }
  }

  //
  // statistics
  //

  public long getCount()
  {
    CacheMapConnection conn = null;
    
    try {
      conn = getConnection();
      PreparedStatement stmt = conn.prepareCount();
      
      ResultSet rs = stmt.executeQuery();

      if (rs != null && rs.next()) {
	long value = rs.getLong(1);
	
	rs.close();
	
	return value;
      }
      
      return -1;
    } catch (SQLException e) {
      log.log(Level.FINE, e.toString(), e);
    } finally {
      if (conn != null)
	conn.close();
    }
    
    return -1;
  }

  public void handleAlarm(Alarm alarm)
  {
    if (_dataSource != null) {
      try {
	removeExpiredData();
      } finally {
	alarm.queue(15000L);
      }
    }
  }

  public void destroy()
  {
    _dataSource = null;
    _freeConn = null;
    
    Alarm alarm = _alarm;
    _alarm = null;

    if (alarm != null)
      alarm.dequeue();
  }

  private CacheMapConnection getConnection()
    throws SQLException
  {
    CacheMapConnection cConn = _freeConn.allocate();

    if (cConn == null) {
      Connection conn = _dataSource.getConnection();
      cConn = new CacheMapConnection(conn);
    }

    return cConn;
  }

  private String serverNameToTableName(String serverName)
  {
    if (serverName == null || "".equals(serverName))
      return "resin_dcache_default";
    
    StringBuilder cb = new StringBuilder();
    cb.append("resin_dcache_");
    
    for (int i = 0; i < serverName.length(); i++) {
      char ch = serverName.charAt(i);

      if ('a' <= ch && ch <= 'z') {
	cb.append(ch);
      }
      else if ('A' <= ch && ch <= 'Z') {
	cb.append(ch);
      }
      else if ('0' <= ch && ch <= '9') {
	cb.append(ch);
      }
      else if (ch == '_') {
	cb.append(ch);
      }
      else
	cb.append('_');
    }

    return cb.toString();
  }

  @Override
  public String toString()
  {
    return getClass().getSimpleName() +  "[" + _tableName + "]";
  }

  class CacheMapConnection {
    private Connection _conn;
    
    private PreparedStatement _loadStatement;
    
    private PreparedStatement _insertStatement;
    private PreparedStatement _updateSaveStatement;
    private PreparedStatement _updateAccessStatement;
    
    private PreparedStatement _removeStatement;
    
    private PreparedStatement _updateValidStatement;
    private PreparedStatement _updateVersionStatement;
    
    private PreparedStatement _timeoutStatement;
    
    private PreparedStatement _countStatement;

    CacheMapConnection(Connection conn)
    {
      _conn = conn;
    }

    PreparedStatement prepareLoad()
      throws SQLException
    {
      if (_loadStatement == null)
	_loadStatement = _conn.prepareStatement(_loadQuery);

      return _loadStatement;
    }

    PreparedStatement prepareInsert()
      throws SQLException
    {
      if (_insertStatement == null)
	_insertStatement = _conn.prepareStatement(_insertQuery);

      return _insertStatement;
    }

    PreparedStatement prepareUpdateSave()
      throws SQLException
    {
      if (_updateSaveStatement == null)
	_updateSaveStatement = _conn.prepareStatement(_updateSaveQuery);

      return _updateSaveStatement;
    }

    PreparedStatement prepareUpdateAccess()
      throws SQLException
    {
      if (_updateAccessStatement == null)
	_updateAccessStatement = _conn.prepareStatement(_updateAccessQuery);

      return _updateAccessStatement;
    }

    PreparedStatement prepareRemove()
      throws SQLException
    {
      if (_removeStatement == null)
	_removeStatement = _conn.prepareStatement(_removeQuery);

      return _removeStatement;
    }

    PreparedStatement prepareUpdateValid()
      throws SQLException
    {
      if (_updateValidStatement == null)
	_updateValidStatement = _conn.prepareStatement(_updateValidQuery);

      return _updateValidStatement;
    }

    PreparedStatement prepareUpdateVersion()
      throws SQLException
    {
      if (_updateVersionStatement == null)
	_updateVersionStatement = _conn.prepareStatement(_updateVersionQuery);

      return _updateVersionStatement;
    }

    PreparedStatement prepareTimeout()
      throws SQLException
    {
      if (_timeoutStatement == null)
	_timeoutStatement = _conn.prepareStatement(_timeoutQuery);

      return _timeoutStatement;
    }

    PreparedStatement prepareCount()
      throws SQLException
    {
      if (_countStatement == null)
	_countStatement = _conn.prepareStatement(_countQuery);

      return _countStatement;
    }

    void close()
    {
      if (_freeConn == null || ! _freeConn.free(this)) {
	try {
	  _conn.close();
	} catch (SQLException e) {
	}
      }
    }
  }
}