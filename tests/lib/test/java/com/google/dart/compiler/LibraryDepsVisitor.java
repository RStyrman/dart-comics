// Copyright (c) 2011, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.google.dart.compiler;

import com.google.dart.compiler.ast.DartClass;
import com.google.dart.compiler.ast.DartIdentifier;
import com.google.dart.compiler.ast.DartNode;
import com.google.dart.compiler.ast.DartNodeTraverser;
import com.google.dart.compiler.ast.DartParameterizedTypeNode;
import com.google.dart.compiler.ast.DartPropertyAccess;
import com.google.dart.compiler.ast.DartTypeNode;
import com.google.dart.compiler.ast.DartUnit;
import com.google.dart.compiler.resolver.Element;
import com.google.dart.compiler.resolver.ElementKind;
import com.google.dart.compiler.resolver.EnclosingElement;
import com.google.dart.compiler.type.InterfaceType;
import com.google.dart.compiler.type.Type;
import com.google.dart.compiler.type.TypeKind;

import java.net.URI;

/**
 * A visitor that fills in {@link LibraryDeps} for a compilation unit.
 */
public class LibraryDepsVisitor extends DartNodeTraverser<Void> {
  /**
   * Fill in {@link LibraryDeps} from a {@link DartUnit}.
   */
  static void exec(DartUnit unit, LibraryDeps.Source source) {
    LibraryDepsVisitor v = new LibraryDepsVisitor(source);
    unit.accept(v);
  }

  private final LibraryDeps.Source source;
  private DartClass currentClass;

  private LibraryDepsVisitor(LibraryDeps.Source source) {
    this.source = source;
  }

  @Override
  public Void visitIdentifier(DartIdentifier node) {
    Element target = node.getTargetSymbol();
    ElementKind kind = ElementKind.of(target);
    // Add dependency on the field or method.
    switch (kind) {
      case FIELD:
      case METHOD: {
        EnclosingElement enclosing = target.getEnclosingElement();
        addHoleIfSuper(node, enclosing);
        if (enclosing.getKind().equals(ElementKind.LIBRARY)) {
          addElementDependency(target);
        }
        break;
      }
    }
    // Add dependency on the computed type of identifiers.
    switch (kind) {
      case NONE:
      case DYNAMIC:
        break;
      default: {
        Type type = target.getType();
        if (type != null) {
          Element element = type.getElement();
          if (ElementKind.of(element).equals(ElementKind.CLASS)) {
            addElementDependency(element);
          }
        }
        break;
      }
    }
    return null;
  }

  @Override
  public Void visitPropertyAccess(DartPropertyAccess node) {
    if (node.getQualifier() instanceof DartIdentifier) {
      DartIdentifier qualifier = (DartIdentifier) node.getQualifier();
      Element target = qualifier.getTargetSymbol();
      if (target != null && target.getKind() == ElementKind.LIBRARY) {
        // Handle library prefixes normally (the prefix part of the qualifier
        // doesn't contain any resolvable library source info)
        return super.visitPropertyAccess(node);
      }
    }
    // Skip rhs of property accesses, so that all identifiers we visit will be 
    // unqualified.
    return node.getQualifier().accept(this);
  }

  @Override
  public Void visitClass(DartClass node) {
    currentClass = node;
    node.visitChildren(this);
    currentClass = null;
    return null;
  }

  @Override
  public Void visitParameterizedTypeNode(DartParameterizedTypeNode node) {
    if (TypeKind.of(node.getType()).equals(TypeKind.INTERFACE)) {
      addElementDependency(((InterfaceType) node.getType()).getElement());
    }
    node.visitChildren(this);
    return null;
  }

  @Override
  public Void visitTypeNode(DartTypeNode node) {
    if (TypeKind.of(node.getType()).equals(TypeKind.INTERFACE)) {
      addElementDependency(((InterfaceType) node.getType()).getElement());
    }
    node.visitChildren(this);
    return null;
  }

  /**
   * Add a 'hole' for the given identifier, if its declaring class is a superclass of the current
   * class. A 'hole' dependency specifies a name that, if filled by something in the library scope,
   * would require this unit to be recompiled.
   * 
   * This situation occurs because names in the library scope bind more strongly than unqualified
   * superclass members.
   */
  private void addHoleIfSuper(DartIdentifier node, Element holder) {
    if (ElementKind.of(holder).equals(ElementKind.CLASS) && holder != currentClass.getSymbol()) {
      source.addHole(node.getTargetName());
    }
  }

  /**
   * Adds a direct dependency on the unit providing given {@link Element}.
   */
  private void addElementDependency(Element elem) {
    DartNode node = elem.getNode();
    if (node != null) {
      DartSource unitSource = (DartSource) node.getSource();
      URI libUri = unitSource.getLibrary().getUri();
      LibraryDeps.Dependency dep =
          new LibraryDeps.Dependency(libUri, unitSource.getName(), unitSource.getLastModified());
      source.addDep(dep);
    }
  }
}
