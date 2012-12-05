// Copyright (c) 2012, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.google.dart.compiler.resolver;

import com.google.common.annotations.VisibleForTesting;
import com.google.dart.compiler.DartCompilationError;
import com.google.dart.compiler.DartCompilerContext;
import com.google.dart.compiler.DartCompilerListener;
import com.google.dart.compiler.ErrorCode;
import com.google.dart.compiler.ast.DartClass;
import com.google.dart.compiler.ast.DartDeclaration;
import com.google.dart.compiler.ast.DartExpression;
import com.google.dart.compiler.ast.DartField;
import com.google.dart.compiler.ast.DartFieldDefinition;
import com.google.dart.compiler.ast.DartFunctionTypeAlias;
import com.google.dart.compiler.ast.DartMethodDefinition;
import com.google.dart.compiler.ast.DartNode;
import com.google.dart.compiler.ast.DartNodeTraverser;
import com.google.dart.compiler.ast.DartTypeParameter;
import com.google.dart.compiler.ast.DartUnit;
import com.google.dart.compiler.ast.LibraryUnit;
import com.google.dart.compiler.ast.Modifiers;
import com.google.dart.compiler.common.SourceInfo;
import com.google.dart.compiler.type.Type;
import com.google.dart.compiler.type.TypeVariable;
import com.google.dart.compiler.type.Types;

import java.util.Collections;
import java.util.List;

/**
 * Builds all class elements and types of a library. Once all libraries
 * of an application have built their types, the library scope per
 * library can be computed.
 */
public class TopLevelElementBuilder {

  public void exec(LibraryUnit library, DartCompilerContext context) {
    assert library.getElement().getScope().isClear();
    for (DartUnit unit : library.getUnits()) {
      unit.accept(new Builder(library.getElement()));
    }
  }

  public void exec(DartUnit unit, DartCompilerContext context) {
    unit.accept(new Builder());
  }

  public void exec(DartClass cls, DartCompilerContext context) {
    cls.accept(new Builder());
  }

  /**
   * Create the scope for this library. First declare imported elements, then declare top-level
   * elements of this library.
   *
   * @param library a library (that must have an empty scope).
   */
  public void fillInLibraryScope(LibraryUnit library, DartCompilerListener listener) {
    Scope scope = library.getElement().getScope();
    assert scope.getElements().isEmpty();

    for (LibraryUnit lib : library.getImports()) {
      String prefix = library.getPrefixOf(lib);
      if (prefix != null) {
        // Put the prefix in the scope.
        scope.declareElement(prefix, lib.getElement());
      } else {
        // Put the elements of the library in the scope.
        for (DartUnit unit : lib.getUnits()) {
          fillInUnitScope(unit, listener, scope);
        }
      }
    }

    for (DartUnit unit : library.getUnits()) {
      fillInUnitScope(unit, listener, scope);
    }
  }

  @VisibleForTesting
  void fillInUnitScope(DartUnit unit, DartCompilerListener listener, Scope scope) {
    for (DartNode node : unit.getTopLevelNodes()) {
      if (node instanceof DartFieldDefinition) {
        for (DartField field : ((DartFieldDefinition) node).getFields()) {
          declare(field.getSymbol(), listener, scope);
        }
      } else {
        declare((Element) node.getSymbol(), listener, scope);
      }
    }
  }

  void compilationError(DartCompilerListener listener, SourceInfo node, ErrorCode errorCode,
                        Object... args) {
    DartCompilationError error = new DartCompilationError(node, errorCode, args);
    listener.onError(error);
  }

  private void declare(Element newElement, DartCompilerListener listener, Scope scope) {
    Element oldElement = scope.declareElement(newElement.getName(), newElement);
    // We had already node with such name, report duplicate.
    if (oldElement != null) {
      // Getter/setter can shared same name, but not setter/setter and getter/getter.
      if (newElement.getModifiers().isAbstractField()
          && oldElement.getModifiers().isAbstractField()) {
        if (newElement.getModifiers().isGetter() && !oldElement.getModifiers().isGetter()) {
          return;
        }
        if (newElement.getModifiers().isSetter() && !oldElement.getModifiers().isSetter()) {
          return;
        }
      }
      // Report two duplicate for both old/new nodes.
      reportDuplicateDeclaration(listener, oldElement);
      reportDuplicateDeclaration(listener, newElement);
    }
  }

  /**
   * Reports {@link ResolverErrorCode#DUPLICATE_TOP_LEVEL_DEFINITION} for given named element.
   */
  @SuppressWarnings("unchecked")
  private void reportDuplicateDeclaration(DartCompilerListener listener, Element element) {
    DartNode node = element.getNode();
    if (node instanceof DartDeclaration) {
      DartNode nameNode = ((DartDeclaration<DartExpression>) node).getName();
      compilationError(
          listener,
          nameNode,
          ResolverErrorCode.DUPLICATE_TOP_LEVEL_DEFINITION,
          nameNode);
    }
  }

  /**
   * Creates a ClassElement for a class.
   */
  private class Builder extends DartNodeTraverser<Void> {

    private LibraryElement library;

    public Builder() {
      this(null);
    }

    public Builder(LibraryElement library) {
      this.library = library;
    }

    @Override
    public Void visitClass(DartClass node) {
      ClassElement element = Elements.classFromNode(node, library);
      List<DartTypeParameter> parameterNodes = node.getTypeParameters();
      List<TypeVariable> typeVariables = Elements.makeTypeVariables(parameterNodes, element);
      element.setType(Types.interfaceType(
          element,
          Collections.<Type>unmodifiableList(typeVariables)));
      node.setSymbol(element);
      node.getName().setSymbol(element);
      return null;
    }

    @Override
    public Void visitFunctionTypeAlias(DartFunctionTypeAlias node) {
      FunctionAliasElement element = Elements.functionTypeAliasFromNode(node, library);
      List<DartTypeParameter> parameterNodes = node.getTypeParameters();
      element.setType(Types.functionAliasType(element,
                                              Elements.makeTypeVariables(parameterNodes, element)));
      node.setSymbol(element);
      return null;
    }

    @Override
    public Void visitMethodDefinition(DartMethodDefinition node) {
      node.setSymbol(Elements.methodFromMethodNode(node, library));
      return null;
    }

    @Override
    public Void visitField(DartField node) {
      Modifiers modifiers = node.getModifiers();
      if (modifiers.isFinal()) {
        // final toplevel fields are implicitly compile-time constants.
        modifiers = modifiers.makeConstant();
      }
      node.setSymbol(Elements.fieldFromNode(node, library, modifiers));
      return null;
    }
  }
}
