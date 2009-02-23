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

package com.caucho.rewrite;

import com.caucho.server.rewrite.MatchFilterChain;

import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;

abstract public class AbstractDispatchRule implements DispatchRule
{
  private Pattern _regexp;

  private ArrayList<RequestPredicate> _predicateList
    = new ArrayList<RequestPredicate>();

  private RequestPredicate []_predicates = new RequestPredicate[0];

  public void setRegexp(Pattern regexp)
  {
    _regexp = regexp;
  }

  public boolean isRequest()
  {
    return true;
  }

  public boolean isInclude()
  {
    return false;
  }

  public boolean isForward()
  {
    return false;
  }

  public void add(RequestPredicate predicate)
  {
    _predicateList.add(predicate);
    _predicates = new RequestPredicate[_predicateList.size()];
    _predicateList.toArray(_predicates);
  }
  
  public FilterChain map(String uri,
			 String queryString,
			 FilterChain next)
  {
    Matcher matcher = null;
    
    if (_regexp != null && (matcher = _regexp.matcher(uri)).find()) {
      String target = null;

      uri = rewrite(matcher, uri);
    
      if (queryString == null)
	target = uri;
      else if (uri.indexOf('?') >= 0)
	target = uri + "&" + queryString;
      else
	target = uri + "?" + queryString;
    
      FilterChain chain = createDispatch(uri, queryString, target, next);

      if (_predicates.length > 0)
	chain = new MatchFilterChain(_predicates, chain, next);

      return chain;
    }
    else
      return next;
  }

  protected FilterChain createDispatch(String uri,
				       String queryString,
				       String target,
				       FilterChain next)
  {
    return next;
  }

  protected String rewrite(Matcher matcher, String uri)
  {
    return uri;
  }

  public String toString()
  {
    return getClass().getSimpleName() + "[regexp=" + _regexp + "]";
  }
}