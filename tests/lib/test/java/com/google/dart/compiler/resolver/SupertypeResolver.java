// Copyright (c) 2012, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.google.dart.compiler.resolver;

import com.google.common.collect.ImmutableSet;
import com.google.dart.compiler.DartCompilerContext;
import com.google.dart.compiler.Source;
import com.google.dart.compiler.ast.DartClass;
import com.google.dart.compiler.ast.DartFunctionTypeAlias;
import com.google.dart.compiler.ast.DartNodeTraverser;
import com.google.dart.compiler.ast.DartTypeNode;
import com.google.dart.compiler.ast.DartTypeParameter;
import com.google.dart.compiler.ast.DartUnit;
import com.google.dart.compiler.type.InterfaceType;
import com.google.dart.compiler.type.Type;
import com.google.dart.compiler.type.TypeKind;

import java.util.List;
import java.util.Set;

/**
 * Resolves the super class, interfaces, default implementation and
 * bounds type parameters of classes in a DartUnit.
 */
public class SupertypeResolver {
  private static final Set<String> BLACK_LISTED_TYPES = ImmutableSet.of(
      "Dynamic",
      "Function",
      "bool",
      "num",
      "int",
      "double",
      "String");

  private ResolutionContext topLevelContext;
  private CoreTypeProvider typeProvider;

  public void exec(DartUnit unit, DartCompilerContext context, CoreTypeProvider typeProvider) {
    exec(unit, context, unit.getLibrary().getElement().getScope(), typeProvider);
  }

  public void exec(DartUnit unit, DartCompilerContext compilerContext, Scope libraryScope,
                   CoreTypeProvider typeProvider) {
    this.typeProvider = typeProvider;
    this.topLevelContext = new ResolutionContext(libraryScope, compilerContext, typeProvider);
    unit.accept(new ClassElementResolver());
  }

  // Resolves super class, interfaces and default class of all classes.
  private class ClassElementResolver extends DartNodeTraverser<Void> {
    @Override
    public Void visitClass(DartClass node) {
      ClassElement classElement = node.getSymbol();

      // Make sure that the type parameters are in scope before resolving the
      // super class and interfaces
      ResolutionContext classContext = topLevelContext.extend(classElement);

      DartTypeNode superclassNode = node.getSuperclass();
      InterfaceType supertype;
      if (superclassNode == null) {
        supertype = typeProvider.getObjectType();
        if (supertype.equals(classElement.getType())) {
          // Object has no supertype.
          supertype = null;
        }
      } else {
        supertype = classContext.resolveClass(superclassNode, false, false);
        supertype.getClass(); // Quick null check.
      }
      if (supertype != null) {
        if (Elements.isTypeNode(superclassNode, BLACK_LISTED_TYPES)
            && !isCoreLibrarySource(node.getSource())) {
          topLevelContext.onError(
              superclassNode,
              ResolverErrorCode.BLACK_LISTED_EXTENDS,
              superclassNode);
        }
        classElement.setSupertype(supertype);
      } else {
        assert classElement.getName().equals("Object") : classElement;
      }

      if (node.getDefaultClass() != null) {
        Element defaultClassElement = classContext.resolveName(node.getDefaultClass().getExpression());
        if (ElementKind.of(defaultClassElement).equals(ElementKind.CLASS)) {
          Elements.setDefaultClass(classElement, (InterfaceType)defaultClassElement.getType());
          node.getDefaultClass().setType(defaultClassElement.getType());
        }
      }

      if (node.getInterfaces() != null) {
        for (DartTypeNode intNode : node.getInterfaces()) {
          InterfaceType intElement = classContext.resolveInterface(intNode, false, false);
          Elements.addInterface(classElement, intElement);
          // Dynamic can not be used as interface.
          if (Elements.isTypeNode(intNode, BLACK_LISTED_TYPES)
              && !isCoreLibrarySource(node.getSource())) {
            topLevelContext.onError(intNode, ResolverErrorCode.BLACK_LISTED_IMPLEMENTS, intNode);
            continue;
          }
          // May be unresolved type, error already reported, ignore.
          if (intElement.getKind() == TypeKind.DYNAMIC) {
            continue;
          }
        }
      }
      setBoundsOnTypeParameters(classElement.getTypeParameters(), classContext);
      return null;
    }

    @Override
    public Void visitFunctionTypeAlias(DartFunctionTypeAlias node) {
      ResolutionContext resolutionContext = topLevelContext.extend(node.getSymbol());
      Elements.addInterface(node.getSymbol(), typeProvider.getFunctionType());
      setBoundsOnTypeParameters(node.getSymbol().getTypeParameters(), resolutionContext);
      return null;
    }
  }

  private void setBoundsOnTypeParameters(List<Type> typeParameters,
                                         ResolutionContext resolutionContext) {
    for (Type typeParameter : typeParameters) {
      TypeVariableElement variable = (TypeVariableElement) typeParameter.getElement();
      DartTypeParameter typeParameterNode = (DartTypeParameter) variable.getNode();
      DartTypeNode boundNode = typeParameterNode.getBound();
      Type bound;
      if (boundNode != null) {
        bound =
            resolutionContext.resolveType(
                boundNode,
                false,
                false,
                ResolverErrorCode.NO_SUCH_TYPE);
        boundNode.setType(bound);
      } else {
        bound = typeProvider.getObjectType();
      }
      variable.setBound(bound);
    }
  }

  /**
   * @return <code>true</code> if given {@link Source} represents code library declaration or
   *         implementation.
   */
  static boolean isCoreLibrarySource(Source source) {
    return Elements.isLibrarySource(source, "corelib.dart")
        || Elements.isLibrarySource(source, "corelib_impl.dart");
  }
}
