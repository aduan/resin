/*
 * Copyright (c) 1998-2007 Caucho Technology -- all rights reserved
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

package javax.inject.manager;

import java.lang.annotation.Annotation;
import java.util.Set;

import javax.context.Contextual;

/**
 * Internal implementation for a Bean
 */
public abstract class Bean<T> implements Contextual<T>
{
  private final Manager manager;

  protected Bean(Manager manager)
  {
    this.manager = manager;
  }

  /**
   * Returns the bean's webbeans manager.
   */
  public Manager getManager()
  {
    return this.manager;
  }

  //
  // metadata for the bean
  //

  /**
   * Returns the bean's binding annotations.
   */
  public abstract Set<Annotation> getBindings();

  /**
   * Returns the bean's deployment type
   */
  public abstract Class<? extends Annotation> getDeploymentType();

  /**
   * Returns the set of injection points, for validation.
   */
  public abstract Set<InjectionPoint> getInjectionPoints();

  /**
   * Returns the bean's name or null if the bean does not have a
   * primary name.
   */
  public abstract String getName();

  /**
   * Returns true if the bean can be null
   */
  public abstract boolean isNullable();

  /**
   * Returns true if the bean is serializable
   */
  public abstract boolean isSerializable();

  /**
   * Returns the bean's scope type.
   */
  public abstract Class<? extends Annotation> getScopeType();

  /**
   * Returns the types that the bean exports for bindings.
   */
  public abstract Set<Class<?>> getTypes();
}