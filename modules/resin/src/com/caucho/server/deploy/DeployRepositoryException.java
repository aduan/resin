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

package com.caucho.server.deploy;

import com.caucho.config.program.ConfigProgram;
import com.caucho.config.program.ContainerProgram;
import com.caucho.config.ConfigException;
import com.caucho.config.program.PropertyValueProgram;
import com.caucho.config.types.PathBuilder;
import com.caucho.config.types.Period;
import com.caucho.config.types.RawString;
import com.caucho.config.types.FileSetType;
import com.caucho.vfs.Path;
import com.caucho.vfs.Vfs;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * General deployment exception.
 */
public class DeployRepositoryException extends RuntimeException {
  public DeployRepositoryException()
  {
  }
  
  public DeployRepositoryException(String msg)
  {
    super(msg);
  }
  
  public DeployRepositoryException(String msg, Throwable cause)
  {
    super(msg, cause);
  }
  
  public DeployRepositoryException(Throwable cause)
  {
    super(cause);
  }
}