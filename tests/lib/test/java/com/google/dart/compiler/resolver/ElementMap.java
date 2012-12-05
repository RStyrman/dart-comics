// Copyright (c) 2012, the Dart project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.google.dart.compiler.resolver;

import com.google.dart.compiler.ast.DartLabel;
import com.google.dart.compiler.ast.DartNode;
import com.google.dart.compiler.ast.Modifiers;
import com.google.dart.compiler.type.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * A more efficient version of {@link com.google.common.collect.Multimap} specifically for
 * {@link Element}
 */
class ElementMap {

  /**
   * A synthetic place holder for an element where the name given to the element map does not match
   * the value returned by {@link Element#getName()} or where there are multiple elements associated
   * with the same name.
   */
  static class ElementHolder implements Element {
    private static final String INTERNAL_ONLY_ERROR = "ElementHolder should not be accessed outside this class";
    
    final String name;
    final Element element;
    ElementHolder nextHolder;

    ElementHolder(String name, Element element) {
      this.name = name;
      this.element = element;
    }

    @Override
    public EnclosingElement getEnclosingElement() {
      throw new AssertionError(INTERNAL_ONLY_ERROR);
    }

    @Override
    public ElementKind getKind() {
      throw new AssertionError(INTERNAL_ONLY_ERROR);
    }

    @Override
    public Modifiers getModifiers() {
      throw new AssertionError(INTERNAL_ONLY_ERROR);
      }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public DartNode getNode() {
      throw new AssertionError(INTERNAL_ONLY_ERROR);
      }

    @Override
    public String getOriginalSymbolName() {
      throw new AssertionError(INTERNAL_ONLY_ERROR);
      }

    @Override
    public Type getType() {
      throw new AssertionError(INTERNAL_ONLY_ERROR);
      }

    @Override
    public boolean isDynamic() {
      throw new AssertionError(INTERNAL_ONLY_ERROR);
      }

    @Override
    public void setNode(DartLabel node) {
      throw new AssertionError(INTERNAL_ONLY_ERROR);
      }
  }

  // Array indexed by hashed name ... length is always power of 2
  private Element[] elements;
  private List<Element> ordered = new ArrayList<Element>();

  ElementMap() {
    clear();
  }

  /**
   * Associate the specified element with the specified name. If the element is already associated
   * with that name, do not associate it again.
   */
  void add(String name, Element element) {

    // Most of the time name equals getName() thus holder == element
    Element newHolder;
    if (name.equals(element.getName())) {
      newHolder = element;
    } else {
      newHolder = new ElementHolder(name, element);
    }

    // 75% fill rate which anecdotal evidence claims is a good threshold for growing
    if ((elements.length >> 2) * 3 <= size()) {
      grow();
    }
    int index = internalAdd(newHolder);
    if (index == -1) {
      ordered.add(element);
      return;
    }

    // Handle existing element with the same name
    Element existingHolder = elements[index];
    if (existingHolder == element) {
      return;
    }
    if (!(existingHolder instanceof ElementHolder)) {
      existingHolder = new ElementHolder(name, existingHolder);
      elements[index] = existingHolder;
    }

    // Check the list for a duplicate element entry, and append if none found
    ElementHolder holder = (ElementHolder) existingHolder;
    while (true) {
      if (holder.element == element) {
        return;
      }
      if (holder.nextHolder == null) {
        holder.nextHolder = new ElementHolder(name, element);
        ordered.add(element);
        return;
      }
      holder = holder.nextHolder;
    }
  }

  void clear() {
    elements = new Element[16];
    ordered.clear();
  }

  /**
   * Answer the element last associated with the specified name.
   * 
   * @return the element or <code>null</code> if none
   */
  Element get(String name) {
    Element element = internalGet(name);
    if (element instanceof ElementHolder) {
      return ((ElementHolder) element).element;
    } else {
      return element;
    }
  }

  /**
   * Answer the element associated with the specified name and kind
   * 
   * @return the element of that kind or <code>null</code> if none
   */
  Element get(String name, ElementKind kind) {
    Element element = internalGet(name);
    if (element instanceof ElementHolder) {
      ElementHolder holder = (ElementHolder) element;
      while (true) {
        element = holder.element;
        if (ElementKind.of(element).equals(kind)) {
          return element;
        }
        holder = holder.nextHolder;
        if (holder == null) {
          break;
        }
      }
    } else {
      if (ElementKind.of(element).equals(kind)) {
        return element;
      }
    }
    return null;
  }

  boolean isEmpty() {
    return ordered.isEmpty();
  }

  int size() {
    return ordered.size();
  }

  List<Element> values() {
    return ordered;
  }

  private void grow() {
    Element[] old = elements;
    elements = new Element[elements.length << 2];
    for (Element element : old) {
      if (element != null) {
        if (internalAdd(element) != -1) {
          // Every element in the array should have a unique name, so there should not be any collision
          throw new RuntimeException("Failed to grow: " + element.getName());
        }
      }
    }
  }

  /**
   * If an element with the given name does not exist in the array, then add the element and return
   * -1 otherwise nothing is added and the index of the existing element returned.
   */
  private int internalAdd(Element element) {
    String name = element.getName();
    int mask = elements.length - 1;
    int probe = name.hashCode() & mask;
    for (int i = probe; i < probe + mask + 1; i++) {
      int index = i & mask;
      Element current = elements[index];
      if (current == null) {
        elements[index] = element;
        return -1;
      }
      if (current.getName().equals(name)) {
        return index;
      }
    }
    throw new AssertionError("overfilled array");
  }

  private Element internalGet(String name) {
    Element element;
    int mask = elements.length - 1;
    int probe = name.hashCode() & mask;
    for (int i = probe; i < probe + mask + 1; i++) {
      element = elements[i & mask];
      if (element == null || element.getName().equals(name)) {
        return element;
      }
    }
    throw new AssertionError("overfilled array");
  }
}
