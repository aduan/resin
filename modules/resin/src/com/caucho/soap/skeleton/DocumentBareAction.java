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
 * @author Emil Ong
 */

package com.caucho.soap.skeleton;

import com.caucho.jaxb.skeleton.Property;
import com.caucho.jaxb.skeleton.WrapperProperty;
import com.caucho.jaxb.JAXBContextImpl;
import static com.caucho.soap.wsdl.WSDLConstants.*;
import com.caucho.util.L10N;
import com.caucho.xml.stream.StaxUtil;

import javax.jws.WebParam;
import javax.jws.WebResult;
import static javax.xml.XMLConstants.*;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.JAXBException;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.ws.Holder;
import javax.xml.ws.WebServiceException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Invokes a SOAP request on a Java POJO method.
 *
 * This class handles the document-literal bare (i.e. non-wrapped) style
 * which JAX-WS maps to methods of at most one input and one output 
 * argument.  Non-void return values count as an output argument and
 * INOUT arguments count as both one input and one output.
 */
public class DocumentBareAction extends AbstractAction {
  private final static Logger log = 
    Logger.getLogger(DocumentBareAction.class.getName());
  private static final L10N L = new L10N(DocumentBareAction.class);
  private static final QName ITEM_NAME = new QName("item");

  private int _inputArgument = -1;
  private final String _messageName;

  public DocumentBareAction(Method method, Method eiMethod,
                            JAXBContextImpl jaxbContext, 
                            String targetNamespace,
                            Marshaller marshaller,
                            Unmarshaller unmarshaller)
    throws JAXBException, WebServiceException
  {
    super(method, eiMethod, 
          jaxbContext, targetNamespace, 
          marshaller, unmarshaller);

    _messageName = _operationName;

    if (_bodyInputs > 1) {
      throw new WebServiceException(L.l("Document bare methods may not have more than one input argument: {0}.{1} has {2} input args", method.getDeclaringClass().getName(), method.getName(), _bodyInputs));
    }

    if (_bodyOutputs > 1)
      throw new WebServiceException(L.l("Document bare methods may not have more than one output argument: {0}.{1} has {2} output args (including the return value)", method.getDeclaringClass().getName(), method.getName(), _bodyOutputs));

    //
    // Fix the argument/response names
    //

    // XXX header args/no args

    if (_bodyInputs == 1) {
      for (int i = 0; i < _bodyArgs.length; i++) {
        if (! (_bodyArgs[i] instanceof OutParameterMarshal)) {
          _inputArgument = i;
          break;
        }
      }

      // XXX check that @WebParam is not set on the argument (and for some
      // reason explicitly set the name to "arg0")
      if ("arg0".equals(_bodyArgs[_inputArgument].getName().getLocalPart())) {

        QName argName = new QName(_targetNamespace, _operationName);
        _bodyArgs[_inputArgument].setName(argName);
      }
      else
        _inputName = _bodyArgs[_inputArgument].getName().getLocalPart();

      // Document bare does something strange with arrays and collections:
      //  They are wrapped and their individual element names are <item>,
      //  except for byte[].
      Class[] parameterTypes = method.getParameterTypes();
      Class inputType = parameterTypes[_inputArgument];

      if ((inputType.isArray() &&
           ! byte.class.equals(inputType.getComponentType()))
          || Collection.class.isAssignableFrom(inputType)) {
        WrapperProperty wrapper = 
          new WrapperProperty(_bodyArgs[_inputArgument]._property,
                              _bodyArgs[_inputArgument].getName(),
                              ITEM_NAME);

        _bodyArgs[_inputArgument].setName(ITEM_NAME);
        _bodyArgs[_inputArgument]._property = wrapper;
      }
    }
    else if (_bodyInputs == 0)
      _inputName = ""; // services/131[89]

    if (_returnMarshal != null) {
      WebResult webResult = _method.getAnnotation(WebResult.class);

      if (webResult == null && eiMethod != null)
        webResult = eiMethod.getAnnotation(WebResult.class);

      if (webResult == null || "".equals(webResult.name()))
        _returnMarshal.setName(new QName(_targetNamespace, _responseName));

      Class returnType = method.getReturnType();

      if ((returnType.isArray() &&
           ! byte.class.equals(returnType.getComponentType()))
          || Collection.class.isAssignableFrom(returnType)) {
        WrapperProperty wrapper = 
          new WrapperProperty(_returnMarshal._property,
                              _returnMarshal.getName(),
                              ITEM_NAME);

        _returnMarshal.setName(ITEM_NAME);
        _returnMarshal._property = wrapper;
      }
    }
  }

  protected void writeMethodInvocation(XMLStreamWriter out, Object []args)
    throws IOException, XMLStreamException, JAXBException
  {
    if (_bodyInputs == 0) {
      out.writeEmptyElement(TARGET_NAMESPACE_PREFIX, 
                            _inputName, 
                            _targetNamespace);
      out.writeNamespace(TARGET_NAMESPACE_PREFIX, _targetNamespace);
    }
    else {
      for (int i = 0; i < _bodyArgs.length; i++)
        _bodyArgs[i].serializeCall(out, args);
    }
  }

  protected Object[] readMethodInvocation(XMLStreamReader header, 
                                          XMLStreamReader in)
    throws IOException, XMLStreamException, JAXBException
  {
    Object[] args = new Object[_arity];

    readHeaders(header, args);

    if (_bodyInputs > 0) {
      for (int i = 0; i < _bodyArgs.length; i++)
        _bodyArgs[i].deserializeCall(in, args);
    }

    return args;
  }

  protected void writeResponse(XMLStreamWriter out, Object value, Object[] args)
    throws IOException, XMLStreamException, JAXBException
  {
    if (_returnMarshal != null && ! _headerReturn)
      _returnMarshal.serializeReply(out, value);

    for (int i = 0; i < _bodyArgs.length; i++)
      _bodyArgs[i].serializeReply(out, args);
  }

  protected Object readResponse(XMLStreamReader in, Object []args)
    throws IOException, XMLStreamException, JAXBException, Throwable
  {
    Object ret = null;

    in.nextTag();
    in.require(XMLStreamReader.START_ELEMENT, null, "Envelope");

    in.nextTag();
    in.require(XMLStreamReader.START_ELEMENT, null, null);

    if ("Header".equals(in.getLocalName())) {
      while (in.nextTag() == XMLStreamReader.START_ELEMENT) {
        String tagName = in.getLocalName();

        ParameterMarshal marshal = _headerArguments.get(tagName);

        if (marshal != null)
          marshal.deserializeReply(in, args);
        else {
          int depth = 1;

          while (depth > 0) {
            switch (in.nextTag()) {
              case XMLStreamReader.START_ELEMENT:
                depth++;
                break;
              case XMLStreamReader.END_ELEMENT:
                depth--;
                break;
              default:
                throw new IOException("expected </Header>");
            }
          }
        }
      }

      in.require(XMLStreamReader.END_ELEMENT, null, "Header");
    }

    in.require(XMLStreamReader.START_ELEMENT, null, "Body");

    in.nextTag();

    if (in.getEventType() == XMLStreamReader.START_ELEMENT &&
        "Fault".equals(in.getLocalName())) {
      Throwable fault = readFault(in);

      if (fault == null)
        throw new WebServiceException(); // XXX

      throw fault;
    }

    if (_returnMarshal != null)
      ret = _returnMarshal.deserializeReply(in, ret);

    for (int i = 0; i < _bodyArgs.length; i++)
      _bodyArgs[i].deserializeReply(in, args);

    in.require(XMLStreamReader.END_ELEMENT, null, "Body");

    in.nextTag();
    in.require(XMLStreamReader.END_ELEMENT, null, "Envelope");

    return ret;
  }

  public void writeWSDLBindingOperation(XMLStreamWriter out, 
                                        String soapNamespaceURI)
    throws XMLStreamException
  {
    out.writeStartElement(WSDL_NAMESPACE, "operation");
    out.writeAttribute("name", _messageName);
    // XXX out.writeAttribute("parameterOrder", "");

    out.writeEmptyElement(soapNamespaceURI, "operation");
    out.writeAttribute("soapAction", _soapAction);

    out.writeStartElement(WSDL_NAMESPACE, "input");
    // XXX
    out.writeEmptyElement(soapNamespaceURI, "body");
    out.writeAttribute("use", "literal");

    out.writeEndElement(); // input

    if (! _isOneway) {
      out.writeStartElement(WSDL_NAMESPACE, "output");
      // XXX
      out.writeEmptyElement(soapNamespaceURI, "body");
      out.writeAttribute("use", "literal");

      out.writeEndElement(); // output
    }

    out.writeEndElement(); // operation
  }

  public void writeWSDLMessages(XMLStreamWriter out, String soapNamespaceURI)
    throws XMLStreamException
  {
    out.writeStartElement(WSDL_NAMESPACE, "message");
    out.writeAttribute("name", _messageName);

    if (_bodyInputs > 0) {
      out.writeEmptyElement(WSDL_NAMESPACE, "part");
      out.writeAttribute("name", _operationName); // XXX partName?
      out.writeAttribute("element", 
                         TARGET_NAMESPACE_PREFIX + ':' + _operationName);
    }

    out.writeEndElement(); // message

    if (! _isOneway) {
      out.writeStartElement(WSDL_NAMESPACE, "message");
      out.writeAttribute("name", _responseName);

      if (_returnMarshal != null) {
        out.writeEmptyElement(WSDL_NAMESPACE, "part");
        out.writeAttribute("name", _responseName);
        out.writeAttribute("element", 
                           TARGET_NAMESPACE_PREFIX + ':' + _responseName);
      }

      out.writeEndElement(); // message
    }
  }
  
  public void writeWSDLOperation(XMLStreamWriter out, String soapNamespaceURI)
    throws XMLStreamException
  {
    out.writeStartElement(WSDL_NAMESPACE, "operation");
    out.writeAttribute("name", _operationName);
    // XXX out.writeAttribute("parameterOrder", "");

    out.writeEmptyElement(WSDL_NAMESPACE, "input");
    out.writeAttribute("message", 
                       TARGET_NAMESPACE_PREFIX + ':' + _messageName);

    if (! _isOneway) {
      out.writeEmptyElement(WSDL_NAMESPACE, "output");
      out.writeAttribute("message", 
                         TARGET_NAMESPACE_PREFIX + ':' + _responseName);
    }

    out.writeEndElement(); // operation
  }

  public void writeSchema(XMLStreamWriter out, String namespace)
    throws XMLStreamException
  {
    if (_bodyInputs > 0) {
      out.writeEmptyElement(XML_SCHEMA_PREFIX, 
                            "element", 
                            W3C_XML_SCHEMA_NS_URI);
      out.writeAttribute("name", _operationName);

      Property property = _bodyArgs[_inputArgument].getProperty();
      out.writeAttribute("type", property.getSchemaType());
    }

    if (_returnMarshal != null) {
      out.writeEmptyElement(XML_SCHEMA_PREFIX, 
                            "element", 
                            W3C_XML_SCHEMA_NS_URI);
      out.writeAttribute("name", _responseName);
      out.writeAttribute("type", _returnMarshal.getProperty().getSchemaType());
    }
  }
}

