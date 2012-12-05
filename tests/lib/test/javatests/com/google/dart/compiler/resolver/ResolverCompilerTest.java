// Copyright (c) 2012, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.google.dart.compiler.resolver;

import static com.google.dart.compiler.common.ErrorExpectation.assertErrors;
import static com.google.dart.compiler.common.ErrorExpectation.errEx;

import com.google.common.base.Joiner;
import com.google.dart.compiler.CompilerTestCase;
import com.google.dart.compiler.DartCompilationError;
import com.google.dart.compiler.ast.DartClass;
import com.google.dart.compiler.ast.DartFunctionTypeAlias;
import com.google.dart.compiler.ast.DartNewExpression;
import com.google.dart.compiler.ast.DartNode;
import com.google.dart.compiler.ast.DartTypeNode;
import com.google.dart.compiler.ast.DartTypeParameter;
import com.google.dart.compiler.ast.DartUnit;
import com.google.dart.compiler.type.FunctionAliasType;
import com.google.dart.compiler.type.Type;
import com.google.dart.compiler.type.TypeVariable;

import java.util.List;

/**
 * Variant of {@link ResolverTest}, which is based on {@link CompilerTestCase}. It is probably
 * slower, not actually unit test, but easier to use if you need access to DartNode's.
 */
public class ResolverCompilerTest extends CompilerTestCase {

  public void test_parameters_withFunctionAlias() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            "typedef List<T> TypeAlias<T, U extends List<T>>(List<T> arg, U u);");
    assertErrors(libraryResult.getCompilationErrors());
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    DartFunctionTypeAlias typeAlias = findTypedef(unit, "TypeAlias");
    assertNotNull(typeAlias);
    FunctionAliasElement element = typeAlias.getSymbol();
    FunctionAliasType ftype = element.getType();
    Type returnType = ftype.getElement().getFunctionType().getReturnType();
    assertEquals("List<TypeAlias.T>", returnType.toString());
    List<? extends Type> arguments = ftype.getArguments();
    assertEquals(2, arguments.size());
    TypeVariable arg0 = (TypeVariable)arguments.get(0);
    assertEquals("T", arg0.getTypeVariableElement().getName());
    Type bound0 = arg0.getTypeVariableElement().getBound();
    assertEquals("Object", bound0.toString());
    TypeVariable arg1 = (TypeVariable)arguments.get(1);
    assertEquals("U", arg1.getTypeVariableElement().getName());
    Type bound1 = arg1.getTypeVariableElement().getBound();
    assertEquals("List<TypeAlias.T>", bound1.toString());
  }

  /**
   * This test checks the class declarations to make sure that symbols are set for
   * all identifiers.  This is useful to the editor and other consumers of the AST.
   */
  public void test_resolution_on_class_decls() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "class A {}",
                "interface B<T> default C {}",
                "class C<T> extends A implements B<T> {}",
                "class D extends C<int> {}",
                "class E implements C<int> {}",
                "class F<T extends A> {}",
                "class G extends F<C<int>> {}",
                "interface H<T> default C<T> {}"));
    assertErrors(libraryResult.getCompilationErrors());
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    List<DartNode> nodes = unit.getTopLevelNodes();
    DartClass A = (DartClass)nodes.get(0);
    assertEquals("A", A.getClassName());
    DartClass B = (DartClass)nodes.get(1);
    assertEquals("B", B.getClassName());
    DartClass C = (DartClass)nodes.get(2);
    assertEquals("C", C.getClassName());
    DartClass D = (DartClass)nodes.get(3);
    assertEquals("D", D.getClassName());
    DartClass E = (DartClass)nodes.get(4);
    assertEquals("E", E.getClassName());
    DartClass F = (DartClass)nodes.get(5);
    assertEquals("F", F.getClassName());
    DartClass G = (DartClass)nodes.get(6);
    assertEquals("G", G.getClassName());
    DartClass H = (DartClass)nodes.get(7);
    assertEquals("H", H.getClassName());

    // class A
    assertNotNull(A.getName().getSymbol());
    assertSame(A.getSymbol(), A.getName().getSymbol());

    // interface B<T> default C
    assertNotNull(B.getName().getSymbol());
    assertSame(B.getName().getSymbol(), B.getSymbol());
    assertEquals(1, B.getTypeParameters().size());
    DartTypeParameter T;
    T = B.getTypeParameters().get(0);
    assertNotNull(T);
    assertNotNull(T.getName().getSymbol());
    assertTrue(T.getName().getSymbol() instanceof TypeVariableElement);
    assertEquals("T", T.getName().getTargetName());
    assertNotNull(B.getDefaultClass().getExpression().getSymbol());
    assertSame(C.getSymbol(), B.getDefaultClass().getExpression().getSymbol());

    // class C<T> extends A implements B<T> {}
    assertNotNull(C.getName().getSymbol());
    assertSame(C.getSymbol(), C.getName().getSymbol());
    assertEquals(1, C.getTypeParameters().size());
    T = C.getTypeParameters().get(0);
    assertNotNull(T);
    assertNotNull(T.getName().getSymbol());
    assertTrue(T.getName().getSymbol() instanceof TypeVariableElement);
    assertEquals("T", T.getName().getTargetName());
    assertSame(A.getSymbol(), C.getSuperclass().getIdentifier().getSymbol());
    assertEquals(1, C.getInterfaces().size());
    DartTypeNode iface = C.getInterfaces().get(0);
    assertNotNull(iface);
    assertSame(B.getSymbol(), iface.getIdentifier().getSymbol());
    assertSame(T.getName().getSymbol(),
        iface.getTypeArguments().get(0).getIdentifier().getSymbol());

    // class D extends C<int> {}
    assertNotNull(D.getName().getSymbol());
    assertSame(D.getSymbol(), D.getName().getSymbol());
    assertEquals(0, D.getTypeParameters().size());
    assertSame(C.getSymbol(), D.getSuperclass().getIdentifier().getSymbol());
    DartTypeNode typeArg;
    typeArg = D.getSuperclass().getTypeArguments().get(0);
    assertNotNull(typeArg.getIdentifier());
    assertEquals("int", typeArg.getIdentifier().getSymbol().getOriginalSymbolName());

    // class E implements C<int> {}
    assertNotNull(E.getName().getSymbol());
    assertSame(E.getSymbol(), E.getName().getSymbol());
    assertEquals(0, E.getTypeParameters().size());
    assertSame(C.getSymbol(), E.getInterfaces().get(0).getIdentifier().getSymbol());
    typeArg = E.getInterfaces().get(0).getTypeArguments().get(0);
    assertNotNull(typeArg.getIdentifier());
    assertEquals("int", typeArg.getIdentifier().getSymbol().getOriginalSymbolName());

    // class F<T extends A> {}",
    assertNotNull(F.getName().getSymbol());
    assertSame(F.getSymbol(), F.getName().getSymbol());
    assertEquals(1, F.getTypeParameters().size());
    T = F.getTypeParameters().get(0);
    assertNotNull(T);
    assertNotNull(T.getName().getSymbol());
    assertTrue(T.getName().getSymbol() instanceof TypeVariableElement);
    assertEquals("T", T.getName().getTargetName());
    assertSame(A.getSymbol(), T.getBound().getIdentifier().getSymbol());

    // class G extends F<C<int>> {}
    assertNotNull(G.getName().getSymbol());
    assertSame(G.getSymbol(), G.getName().getSymbol());
    assertEquals(0, G.getTypeParameters().size());
    assertNotNull(G.getSuperclass());
    assertSame(F.getSymbol(), G.getSuperclass().getIdentifier().getSymbol());
    typeArg = G.getSuperclass().getTypeArguments().get(0);
    assertSame(C.getSymbol(), typeArg.getIdentifier().getSymbol());
    assertEquals("int",
        typeArg.getTypeArguments().get(0).getIdentifier().getSymbol().getOriginalSymbolName());

    // class H<T> extends C<T> {}",
    assertNotNull(H.getName().getSymbol());
    assertSame(H.getSymbol(), H.getName().getSymbol());
    assertEquals(1, H.getTypeParameters().size());
    T = H.getTypeParameters().get(0);
    assertNotNull(T);
    assertNotNull(T.getName().getSymbol());
    assertTrue(T.getName().getSymbol() instanceof TypeVariableElement);
    assertNotNull(H.getDefaultClass().getExpression().getSymbol());
    assertSame(C.getSymbol(), H.getDefaultClass().getExpression().getSymbol());
    // This type parameter T resolves to the Type variable on the default class, so it
    // isn't the same type variable instance specified in this interface declaration,
    // though it must have the same name.
    DartTypeParameter defaultT = H.getDefaultClass().getTypeParameters().get(0);
    assertNotNull(defaultT.getName().getSymbol());
    assertTrue(defaultT.getName().getSymbol() instanceof TypeVariableElement);
    assertEquals(T.getName().getSymbol().getName(), defaultT.getName().getSymbol().getName());
  }

  /**
   * We should be able to resolve implicit default constructor.
   */
  public void test_resolveConstructor_implicit() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "class F {",
                "}",
                "class Test {",
                "  foo() {",
                "    new F();",
                "  }",
                "}"));
    assertErrors(libraryResult.getCompilationErrors());
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    DartNewExpression newExpression = findNewExpression(unit, "new F()");
    ConstructorElement constructorElement = newExpression.getSymbol();
    assertNotNull(constructorElement);
    assertNull(constructorElement.getNode());
  }

  public void test_resolveConstructor_noSuchConstructor() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "class A {",
                "}",
                "class Test {",
                "  foo() {",
                "    new A.foo();",
                "  }",
                "}"));
    assertErrors(
                 libraryResult.getCompilationErrors(),
        errEx(ResolverErrorCode.NEW_EXPRESSION_NOT_CONSTRUCTOR, 5, 9, 5));
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    DartNewExpression newExpression = findNewExpression(unit, "new A.foo()");
    ConstructorElement constructorElement = newExpression.getSymbol();
    assertNull(constructorElement);
  }

  /**
   * We should be able to resolve implicit default constructor.
   */
  public void test_resolveInterfaceConstructor_implicitDefault_noInterface_noFactory()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "}",
                "class F implements I {",
                "}",
                "class Test {",
                "  foo() {",
                "    new I();",
                "  }",
                "}"));
    assertErrors(libraryResult.getCompilationErrors());
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    DartNewExpression newExpression = findNewExpression(unit, "new I()");
    ConstructorElement constructorElement = newExpression.getSymbol();
    assertNotNull(constructorElement);
    assertNull(constructorElement.getNode());
  }

  /**
   * We should be able to resolve implicit default constructor.
   */
  public void test_resolveInterfaceConstructor_implicitDefault_hasInterface_noFactory()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "}",
                "class F implements I {",
                "}",
                "class Test {",
                "  foo() {",
                "    new I();",
                "  }",
                "}"));
    assertErrors(libraryResult.getCompilationErrors());
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    DartNewExpression newExpression = findNewExpression(unit, "new I()");
    ConstructorElement constructorElement = newExpression.getSymbol();
    assertNotNull(constructorElement);
    assertNull(constructorElement.getNode());
  }

  /**
   * We should be able to resolve implicit default constructor.
   */
  public void test_resolveInterfaceConstructor_implicitDefault_noInterface_hasFactory()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "}",
                "class F implements I {",
                "  F();",
                "}",
                "class Test {",
                "  foo() {",
                "    new I();",
                "  }",
                "}"));
    assertErrors(libraryResult.getCompilationErrors());
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    DartNewExpression newExpression = findNewExpression(unit, "new I()");
    DartNode constructorNode = newExpression.getSymbol().getNode();
    assertEquals(true, constructorNode.toSource().contains("F()"));
  }

  /**
   * If "const I()" is used, then constructor should be "const".
   */
  public void test_resolveInterfaceConstructor_const() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "  I(int x);",
                "}",
                "class F implements I {",
                "  F(int y) {}",
                "}",
                "class Test {",
                "  foo() {",
                "    const I(0);",
                "  }",
                "}"));
    assertErrors(
        libraryResult.getCompilationErrors(),
        errEx(ResolverErrorCode.CONST_AND_NONCONST_CONSTRUCTOR, 9, 5, 10));
  }

  /**
   * From specification 0.05, 11/14/2011.
   * <p>
   * A constructor kI of I corresponds to a constructor kF of its factory class F if either
   * <ul>
   * <li>F does not implement I and kI and kF have the same name, OR
   * <li>F implements I and either
   * <ul>
   * <li>kI is named NI and kF is named NF, OR
   * <li>kI is named NI.id and kF is named NF.id.
   * </ul>
   * </ul>
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=521
   */
  public void test_resolveInterfaceConstructor_whenFactoryImplementsInterface_nameIsIdentifier()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "  I(int x);",
                "}",
                "class F implements I {",
                "  F(int y) {}",
                "  factory I(int y) {}",
                "}",
                "class Test {",
                "  foo() {",
                "    new I(0);",
                "  }",
                "}"));
    assertErrors(libraryResult.getCompilationErrors());
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    DartNewExpression newExpression = findNewExpression(unit, "new I(0)");
    DartNode constructorNode = newExpression.getSymbol().getNode();
    assertEquals(true, constructorNode.toSource().contains("F(int y)"));
  }

  /**
   * From specification 0.05, 11/14/2011.
   * <p>
   * A constructor kI of I corresponds to a constructor kF of its factory class F if either
   * <ul>
   * <li>F does not implement I and kI and kF have the same name, OR
   * <li>F implements I and either
   * <ul>
   * <li>kI is named NI and kF is named NF , OR
   * <li>kI is named NI.id and kF is named NF.id.
   * </ul>
   * </ul>
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=521
   */
  public void test_resolveInterfaceConstructor_whenFactoryImplementsInterface_nameIsQualified()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "  I.foo(int x);",
                "}",
                "class F implements I {",
                "  F.foo(int y) {}",
                "  factory I.foo(int y) {}",
                "}",
                "class Test {",
                "  foo() {",
                "    new I.foo(0);",
                "  }",
                "}"));
    assertErrors(libraryResult.getCompilationErrors());
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    // "new I.foo()" - good
    {
      DartNewExpression newExpression = findNewExpression(unit, "new I.foo(0)");
      DartNode constructorNode = newExpression.getSymbol().getNode();
      assertEquals(true, constructorNode.toSource().contains("F.foo(int y)"));
    }
  }

  /**
   * From specification 0.05, 11/14/2011.
   * <p>
   * A constructor kI of I corresponds to a constructor kF of its factory class F if either
   * <ul>
   * <li>F does not implement I and kI and kF have the same name, OR
   * <li>F implements I and either
   * <ul>
   * <li>kI is named NI and kF is named NF , OR
   * <li>kI is named NI.id and kF is named NF.id.
   * </ul>
   * </ul>
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=521
   */
  public void test_resolveInterfaceConstructor_whenFactoryImplementsInterface_negative()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "  I(int x);",
                "  I.foo(int x);",
                "}",
                "class F implements I {",
                "  factory I.foo(int x) {}",
                "}",
                "class Test {",
                "  foo() {",
                "    new I(0);",
                "    new I.foo(0);",
                "  }",
                "}"));
    // Check errors.
    {
      List<DartCompilationError> errors = libraryResult.getCompilationErrors();
      assertErrors(
          errors,
          errEx(ResolverErrorCode.DEFAULT_CONSTRUCTOR_UNRESOLVED, 2, 3, 9),
          errEx(ResolverErrorCode.DEFAULT_CONSTRUCTOR_UNRESOLVED, 3, 3, 13),
          errEx(ResolverErrorCode.DEFAULT_CONSTRUCTOR_UNRESOLVED, 10, 9, 1),
          errEx(ResolverErrorCode.DEFAULT_CONSTRUCTOR_UNRESOLVED, 11, 9, 5));
      {
        String message = errors.get(0).getMessage();
        assertTrue(message, message.contains("'F'"));
        assertTrue(message, message.contains("'F'"));
      }
      {
        String message = errors.get(1).getMessage();
        assertTrue(message, message.contains("'F.foo'"));
        assertTrue(message, message.contains("'F'"));
      }
    }
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    // "new I()" - no such constructor, has other constructors, so no implicit default.
    {
      DartNewExpression newExpression = findNewExpression(unit, "new I(0)");
      assertEquals(null, newExpression.getSymbol());
    }
    // "new I.foo()" - would be valid, if not "F implements I", but here invalid
    {
      DartNewExpression newExpression = findNewExpression(unit, "new I.foo(0)");
      assertEquals(null, newExpression.getSymbol());
    }
  }

  /**
   * From specification 0.05, 11/14/2011.
   * <p>
   * A constructor kI of I corresponds to a constructor kF of its factory class F if either
   * <ul>
   * <li>F does not implement I and kI and kF have the same name, OR
   * <li>F implements I and either
   * <ul>
   * <li>kI is named NI and kF is named NF , OR
   * <li>kI is named NI.id and kF is named NF.id.
   * </ul>
   * </ul>
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=521
   */
  public void test_resolveInterfaceConstructor_noFactoryImplementsInterface() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "  I(int x);",
                "  I.foo(int x);",
                "}",
                "class F {",
                "  F.foo(int y) {}",
                "  factory I(int y) {}",
                "  factory I.foo(int y) {}",
                "}",
                "class Test {",
                "  foo() {",
                "    new I(0);",
                "    new I.foo(0);",
                "  }",
                "}"));
    assertErrors(libraryResult.getCompilationErrors());
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    // "new I()"
    {
      DartNewExpression newExpression = findNewExpression(unit, "new I(0)");
      DartNode constructorNode = newExpression.getSymbol().getNode();
      assertEquals(true, constructorNode.toSource().contains("I(int y)"));
    }
    // "new I.foo()"
    {
      DartNewExpression newExpression = findNewExpression(unit, "new I.foo(0)");
      DartNode constructorNode = newExpression.getSymbol().getNode();
      assertEquals(true, constructorNode.toSource().contains("I.foo(int y)"));
    }
  }

  /**
   * From specification 0.05, 11/14/2011.
   * <p>
   * A constructor kI of I corresponds to a constructor kF of its factory class F if either
   * <ul>
   * <li>F does not implement I and kI and kF have the same name, OR
   * <li>F implements I and either
   * <ul>
   * <li>kI is named NI and kF is named NF , OR
   * <li>kI is named NI.id and kF is named NF.id.
   * </ul>
   * </ul>
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=521
   */
  public void test_resolveInterfaceConstructor_noFactoryImplementsInterface_negative()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "  I.foo(int x);",
                "}",
                "class F {",
                "}",
                "class Test {",
                "  foo() {",
                "    new I.foo(0);",
                "  }",
                "}"));
    // Check errors.
    {
      List<DartCompilationError> errors = libraryResult.getCompilationErrors();
      assertErrors(
          errors,
          errEx(ResolverErrorCode.DEFAULT_CONSTRUCTOR_UNRESOLVED, 2, 3, 13),
          errEx(ResolverErrorCode.DEFAULT_CONSTRUCTOR_UNRESOLVED, 8, 9, 5));
      {
        String message = errors.get(0).getMessage();
        assertTrue(message, message.contains("'I.foo'"));
        assertTrue(message, message.contains("'F'"));
      }
    }
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    // "new I.foo()"
    {
      DartNewExpression newExpression = findNewExpression(unit, "new I.foo(0)");
      assertEquals(null, newExpression.getSymbol());
    }
  }

  /**
   * From specification 0.05, 11/14/2011.
   * <p>
   * It is a compile-time error if kI and kF do not have the same number of required parameters.
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=521
   */
  public void test_resolveInterfaceConstructor_hasByName_negative_notSameNumberOfRequiredParameters()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "  I.foo(int x);",
                "}",
                "class F implements I {",
                "  factory F.foo() {}",
                "}",
                "class Test {",
                "  foo() {",
                "    new I.foo();",
                "  }",
                "}"));
    assertErrors(libraryResult.getTypeErrors());
    // Check errors.
    {
      List<DartCompilationError> errors = libraryResult.getCompilationErrors();
      assertErrors(
          errors,
          errEx(ResolverErrorCode.DEFAULT_CONSTRUCTOR_NUMBER_OF_REQUIRED_PARAMETERS, 2, 3, 13));
      {
        String message = errors.get(0).getMessage();
        assertTrue(message, message.contains("'F.foo'"));
        assertTrue(message, message.contains("'F'"));
        assertTrue(message, message.contains("0"));
        assertTrue(message, message.contains("1"));
        assertTrue(message, message.contains("'F.foo'"));
      }
    }
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    // "new I.foo()" - resolved, but we produce error.
    {
      DartNewExpression newExpression = findNewExpression(unit, "new I.foo()");
      DartNode constructorNode = newExpression.getSymbol().getNode();
      assertEquals(true, constructorNode.toSource().contains("F.foo()"));
    }
  }

  /**
   * From specification 0.05, 11/14/2011.
   * <p>
   * It is a compile-time error if kI and kF do not have identically named optional parameters,
   * declared in the same order.
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=521
   */
  public void test_resolveInterfaceConstructor_hasByName_negative_notSameNamedParameters()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "  I.foo(int a, [int b, int c]);",
                "  I.bar(int a, [int b, int c]);",
                "  I.baz(int a, [int b]);",
                "}",
                "class F implements I {",
                "  factory F.foo(int any, [int b = 1]) {}",
                "  factory F.bar(int any, [int c = 1, int b = 2]) {}",
                "  factory F.baz(int any, [int c = 1]) {}",
                "}",
                "class Test {",
                "  foo() {",
                "    new I.foo(0);",
                "    new I.bar(0);",
                "    new I.baz(0);",
                "  }",
                "}"));
    assertErrors(libraryResult.getTypeErrors());
    // Check errors.
    {
      List<DartCompilationError> errors = libraryResult.getCompilationErrors();
      assertErrors(
          errors,
          errEx(ResolverErrorCode.DEFAULT_CONSTRUCTOR_NAMED_PARAMETERS, 2, 3, 29),
          errEx(ResolverErrorCode.DEFAULT_CONSTRUCTOR_NAMED_PARAMETERS, 3, 3, 29),
          errEx(ResolverErrorCode.DEFAULT_CONSTRUCTOR_NAMED_PARAMETERS, 4, 3, 22));
      {
        String message = errors.get(0).getMessage();
        assertTrue(message, message.contains("'I.foo'"));
        assertTrue(message, message.contains("'F'"));
        assertTrue(message, message.contains("[b]"));
        assertTrue(message, message.contains("[b, c]"));
        assertTrue(message, message.contains("'F.foo'"));
      }
      {
        String message = errors.get(1).getMessage();
        assertTrue(message, message.contains("'I.bar'"));
        assertTrue(message, message.contains("'F'"));
        assertTrue(message, message.contains("[c, b]"));
        assertTrue(message, message.contains("[b, c]"));
        assertTrue(message, message.contains("'F.bar'"));
      }
      {
        String message = errors.get(2).getMessage();
        assertTrue(message, message.contains("'I.baz'"));
        assertTrue(message, message.contains("'F'"));
        assertTrue(message, message.contains("[b]"));
        assertTrue(message, message.contains("[c]"));
        assertTrue(message, message.contains("'F.baz'"));
      }
    }
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    // "new I.foo()" - resolved, but we produce error.
    {
      DartNewExpression newExpression = findNewExpression(unit, "new I.foo(0)");
      DartNode constructorNode = newExpression.getSymbol().getNode();
      assertEquals(true, constructorNode.toSource().contains("F.foo("));
    }
    // "new I.bar()" - resolved, but we produce error.
    {
      DartNewExpression newExpression = findNewExpression(unit, "new I.bar(0)");
      DartNode constructorNode = newExpression.getSymbol().getNode();
      assertEquals(true, constructorNode.toSource().contains("F.bar("));
    }
    // "new I.baz()" - resolved, but we produce error.
    {
      DartNewExpression newExpression = findNewExpression(unit, "new I.baz(0)");
      DartNode constructorNode = newExpression.getSymbol().getNode();
      assertEquals(true, constructorNode.toSource().contains("F.baz("));
    }
  }
}
