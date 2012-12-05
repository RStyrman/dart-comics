// Copyright (c) 2011, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.google.dart.compiler.resolver;

import com.google.dart.compiler.ErrorCode;
import com.google.dart.compiler.ast.DartCatchBlock;
import com.google.dart.compiler.ast.DartFunction;
import com.google.dart.compiler.ast.DartFunctionTypeAlias;
import com.google.dart.compiler.ast.DartNode;
import com.google.dart.compiler.ast.DartNodeTraverser;
import com.google.dart.compiler.ast.DartParameter;
import com.google.dart.compiler.ast.DartTypeNode;
import com.google.dart.compiler.ast.DartTypeParameter;
import com.google.dart.compiler.type.DynamicType;
import com.google.dart.compiler.type.FunctionType;
import com.google.dart.compiler.type.Type;
import com.google.dart.compiler.type.TypeVariable;
import com.google.dart.compiler.type.Types;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared visitor between Resolver and MemberBuilder.
 */
abstract class ResolveVisitor extends DartNodeTraverser<Element> {
  private final CoreTypeProvider typeProvider;

  ResolveVisitor(CoreTypeProvider typeProvider) {
    this.typeProvider = typeProvider;
  }

  abstract ResolutionContext getContext();

  final MethodElement resolveFunction(DartFunction node, MethodElement element) {
    for (DartParameter parameter : node.getParams()) {
      Elements.addParameter(element, (VariableElement) parameter.accept(this));
    }
    Type returnType =
        resolveType(
            node.getReturnTypeNode(),
            element.getModifiers().isStatic(),
            element.getModifiers().isFactory(),
            TypeErrorCode.NO_SUCH_TYPE);
    ClassElement functionElement = typeProvider.getFunctionType().getElement();
    FunctionType type = Types.makeFunctionType(getContext(), functionElement,
                                               element.getParameters(), returnType);
    Elements.setType(element, type);
    return element;
  }

  private void bindReturnGenerics(DartTypeNode node) {
    for (DartTypeNode typeNode : node.getTypeArguments()) {
      if (ElementKind.of(typeNode.getType().getElement()) != ElementKind.TYPE_VARIABLE) {
        bindReturnGenerics(typeNode);
        continue;
      }
      bindTypeVariable((TypeVariableElement) typeNode.getType().getElement());
    }
  }

  final FunctionAliasElement resolveFunctionAlias(DartFunctionTypeAlias node) {
    // The purpose of this to find generic types and make sure they are bound to object.
    DartTypeNode returnNode = node.getReturnTypeNode();
    if (returnNode != null) {
      bindReturnGenerics(returnNode);
    }
    FunctionAliasElement funcAlias = node.getSymbol();
    for (Type type : funcAlias.getTypeParameters()) {
      TypeVariableElement typeVar = (TypeVariableElement) type.getElement();
      getContext().getScope().declareElement(typeVar.getName(), typeVar);
    }
    return null;
  }

  void bindTypeVariable(TypeVariableElement variable) {
    DartTypeParameter typeParameterNode = (DartTypeParameter) variable.getNode();
    DartTypeNode boundNode = typeParameterNode.getBound();
    Type bound;
    if (boundNode != null) {
      bound = getContext().resolveType(boundNode, true, isFactoryContext(), ResolverErrorCode.NO_SUCH_TYPE);
      boundNode.setType(bound);
    } else {
      bound = typeProvider.getObjectType();
    }
    variable.setBound(bound);
  }

  void bindTypeVariables(List<TypeVariable> typeVariables) {
    if (typeVariables == null) {
      return;
    }
    for (TypeVariable typeParameter : typeVariables) {
      TypeVariableElement variable = (TypeVariableElement) typeParameter.getElement();
      bindTypeVariable(variable);
      getContext().getScope().declareElement(variable.getName(), variable);
    }
  }

  abstract boolean isStaticContext();

  abstract boolean isFactoryContext();

  @Override
  public Element visitParameter(DartParameter node) {
    ErrorCode typeErrorCode =
        node.getParent() instanceof DartCatchBlock
            ? ResolverErrorCode.NO_SUCH_TYPE
            : TypeErrorCode.NO_SUCH_TYPE;
    Type type = resolveType(node.getTypeNode(), isStaticContext(), isFactoryContext(), typeErrorCode);
    VariableElement element = Elements.parameterElement(node, node.getParameterName(),
                                                        node.getModifiers());
    List<DartParameter> functionParameters = node.getFunctionParameters();
    if (functionParameters != null) {
      List<VariableElement> parameterElements =
          new ArrayList<VariableElement>(functionParameters.size());
      for (DartParameter parameter: functionParameters) {
        parameterElements.add((VariableElement) parameter.accept(this));
      }
      ClassElement functionElement = typeProvider.getFunctionType().getElement();
      type = Types.makeFunctionType(getContext(), functionElement, parameterElements, type);
    }
    Elements.setType(element, type);
    return recordElement(node, element);
  }

  final Type resolveType(DartTypeNode node, boolean isStatic, boolean isFactory,
                         ErrorCode errorCode) {
    if (node == null) {
      return getTypeProvider().getDynamicType();
    }
    assert node.getType() == null || node.getType() instanceof DynamicType;
    Type type = getContext().resolveType(node, isStatic, isFactory, errorCode);
    if (type == null) {
      type = getTypeProvider().getDynamicType();
    }
    node.setType(type);
    recordElement(node.getIdentifier(), type.getElement());
    return type;
  }

  protected <E extends Element> E recordElement(DartNode node, E element) {
    node.getClass();
    if (element == null) {
      // TypeAnalyzer will diagnose unresolved identifiers.
      return null;
    }
    node.setSymbol(element);
    return element;
  }

  CoreTypeProvider getTypeProvider() {
    return typeProvider;
  }
}
