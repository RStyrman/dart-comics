// Copyright (c) 2012, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.google.dart.compiler.type;

import static com.google.dart.compiler.common.ErrorExpectation.assertErrors;
import static com.google.dart.compiler.common.ErrorExpectation.errEx;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.dart.compiler.CompilerTestCase;
import com.google.dart.compiler.DartArtifactProvider;
import com.google.dart.compiler.DartCompilationError;
import com.google.dart.compiler.DartCompiler;
import com.google.dart.compiler.DartCompilerErrorCode;
import com.google.dart.compiler.DartCompilerListener;
import com.google.dart.compiler.MockArtifactProvider;
import com.google.dart.compiler.MockLibrarySource;
import com.google.dart.compiler.ast.DartClass;
import com.google.dart.compiler.ast.DartExprStmt;
import com.google.dart.compiler.ast.DartExpression;
import com.google.dart.compiler.ast.DartField;
import com.google.dart.compiler.ast.DartFieldDefinition;
import com.google.dart.compiler.ast.DartFunctionExpression;
import com.google.dart.compiler.ast.DartIdentifier;
import com.google.dart.compiler.ast.DartInvocation;
import com.google.dart.compiler.ast.DartMethodDefinition;
import com.google.dart.compiler.ast.DartNewExpression;
import com.google.dart.compiler.ast.DartNode;
import com.google.dart.compiler.ast.DartNodeTraverser;
import com.google.dart.compiler.ast.DartParameter;
import com.google.dart.compiler.ast.DartUnit;
import com.google.dart.compiler.ast.DartUnqualifiedInvocation;
import com.google.dart.compiler.common.Symbol;
import com.google.dart.compiler.parser.ParserErrorCode;
import com.google.dart.compiler.resolver.ClassElement;
import com.google.dart.compiler.resolver.Element;
import com.google.dart.compiler.resolver.ElementKind;
import com.google.dart.compiler.resolver.EnclosingElement;
import com.google.dart.compiler.resolver.MethodElement;
import com.google.dart.compiler.resolver.ResolverErrorCode;
import com.google.dart.compiler.resolver.TypeErrorCode;

import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.util.List;

/**
 * Variant of {@link TypeAnalyzerTest}, which is based on {@link CompilerTestCase}. It is probably
 * slower, not actually unit test, but easier to use if you need access to DartNode's.
 */
public class TypeAnalyzerCompilerTest extends CompilerTestCase {
  /**
   * Tests that we correctly provide {@link Element#getEnclosingElement()} for method of class.
   */
  public void test_resolveClassMethod() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "class Object {}",
                "class Test {",
                "  foo() {",
                "    f();",
                "  }",
                "  f() {",
                "  }",
                "}"));
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    // find f() invocation
    DartInvocation invocation = findInvocationSimple(unit, "f()");
    assertNotNull(invocation);
    // referenced Element should be resolved to MethodElement
    Element methodElement = invocation.getReferencedElement();
    assertNotNull(methodElement);
    assertSame(ElementKind.METHOD, methodElement.getKind());
    assertEquals("f", ((MethodElement) methodElement).getOriginalSymbolName());
    // enclosing Element of MethodElement is ClassElement
    EnclosingElement classElement = methodElement.getEnclosingElement();
    assertNotNull(classElement);
    assertSame(ElementKind.CLASS, classElement.getKind());
    assertEquals("Test", ((ClassElement) classElement).getOriginalSymbolName());
  }

  /**
   * Test that local {@link DartFunctionExpression} has {@link Element} with enclosing
   * {@link Element}.
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=145
   */
  public void test_resolveLocalFunction() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "class Object {}",
                "class Test {",
                "  foo() {",
                "    f() {",
                "    }",
                "    f();",
                "  }",
                "}"));
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    // find f() invocation
    DartInvocation invocation = findInvocationSimple(unit, "f()");
    assertNotNull(invocation);
    // referenced Element should be resolved to MethodElement
    Element functionElement = invocation.getReferencedElement();
    assertNotNull(functionElement);
    assertSame(ElementKind.FUNCTION_OBJECT, functionElement.getKind());
    assertEquals("f", ((MethodElement) functionElement).getOriginalSymbolName());
    // enclosing Element of this FUNCTION_OBJECT is enclosing method
    EnclosingElement enclosingMethodElement = functionElement.getEnclosingElement();
    assertNotNull(enclosingMethodElement);
    assertSame(ElementKind.METHOD, enclosingMethodElement.getKind());
    assertEquals("foo", ((MethodElement) enclosingMethodElement).getName());
    // use EnclosingElement methods implementations in MethodElement
    assertEquals(false, enclosingMethodElement.isInterface());
    assertEquals(true, Iterables.isEmpty(enclosingMethodElement.getMembers()));
    assertEquals(null, enclosingMethodElement.lookupLocalElement("f"));
  }

  /**
   * Language specification requires that factory should be declared in class. However declaring
   * factory on top level should not cause exceptions in compiler.
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=345
   */
  public void test_badTopLevelFactory() throws Exception {
    AnalyzeLibraryResult libraryResult = analyzeLibrary("Test.dart", "factory foo() {}");
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    DartMethodDefinition factory = (DartMethodDefinition) unit.getTopLevelNodes().get(0);
    assertNotNull(factory);
    // this factory has name, which is allowed for normal method
    assertEquals(true, factory.getName() instanceof DartIdentifier);
    assertEquals("foo", ((DartIdentifier) factory.getName()).getTargetName());
    // compilation error expected
    assertBadTopLevelFactoryError(libraryResult);
  }

  /**
   * Asserts that given {@link AnalyzeLibraryResult} contains {@link DartCompilationError} for
   * invalid factory on top level.
   */
  private void assertBadTopLevelFactoryError(AnalyzeLibraryResult libraryResult) {
    List<DartCompilationError> compilationErrors = libraryResult.getCompilationErrors();
    assertEquals(1, compilationErrors.size());
    DartCompilationError compilationError = compilationErrors.get(0);
    assertEquals(ParserErrorCode.DISALLOWED_FACTORY_KEYWORD, compilationError.getErrorCode());
    assertEquals(1, compilationError.getLineNumber());
    assertEquals(1, compilationError.getColumnNumber());
    assertEquals("factory".length(), compilationError.getLength());
  }

  /**
   * @return the {@link DartInvocation} with given source. This is inaccurate approach, but good
   *         enough for specific tests.
   */
  private static DartInvocation findInvocationSimple(DartNode rootNode,
      final String invocationString) {
    final DartInvocation invocationRef[] = new DartInvocation[1];
    rootNode.accept(new DartNodeTraverser<Void>() {
      @Override
      public Void visitInvocation(DartInvocation node) {
        if (node.toSource().equals(invocationString)) {
          invocationRef[0] = node;
        }
        return super.visitInvocation(node);
      }
    });
    return invocationRef[0];
  }

  /**
   * From specification 0.05, 11/14/2011.
   * <p>
   * It is a static type warning if the type of the nth required formal parameter of kI is not
   * identical to the type of the nth required formal parameter of kF.
   * <p>
   * It is a static type warning if the types of named optional parameters with the same name differ
   * between kI and kF .
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=521
   */
  public void test_resolveInterfaceConstructor_hasByName_negative_notSameParametersType()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "  I.foo(int a, [int b, int c]);",
                "}",
                "class F implements I {",
                "  factory F.foo(num any, [bool b, Object c]) {}",
                "}",
                "class Test {",
                "  foo() {",
                "    new I.foo(0);",
                "  }",
                "}"));
    // No compilation errors.
    assertErrors(libraryResult.getCompilationErrors());
    // Check type warnings.
    {
      List<DartCompilationError> errors = libraryResult.getTypeErrors();
      assertErrors(errors, errEx(TypeErrorCode.DEFAULT_CONSTRUCTOR_TYPES, 2, 3, 29));
      assertEquals(
          "Constructor 'I.foo' in 'I' has parameters types (int,int,int), doesn't match 'F.foo' in 'F' with (num,bool,Object)",
          errors.get(0).getMessage());
    }
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
    // "new I.foo()" - resolved, but we produce error.
    {
      DartNewExpression newExpression = findNewExpression(unit, "new I.foo(0)");
      DartNode constructorNode = newExpression.getSymbol().getNode();
      assertEquals(true, constructorNode.toSource().contains("F.foo("));
    }
  }

  /**
   * There was problem that <code>this.fieldName</code> constructor parameter had no type, so we
   * produced incompatible interface/default class warning.
   */
  public void test_resolveInterfaceConstructor_sameParametersType_thisFieldParameter()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            "Test.dart",
            Joiner.on("\n").join(
                "interface I factory F {",
                "  I(int a);",
                "}",
                "class F implements I {",
                "  int a;",
                "  F(this.a) {}",
                "}"));
    // Check that parameter has resolved type.
    {
      DartUnit unit = libraryResult.getLibraryUnitResult().getUnits().iterator().next();
      DartClass classF = (DartClass) unit.getTopLevelNodes().get(1);
      DartMethodDefinition methodF = (DartMethodDefinition) classF.getMembers().get(1);
      DartParameter parameter = methodF.getFunction().getParams().get(0);
      assertEquals("int", parameter.getSymbol().getType().toString());
    }
    // No errors or type warnings.
    assertErrors(libraryResult.getCompilationErrors());
    assertErrors(libraryResult.getTypeErrors());
  }

  /**
   * In contrast, if A is intended to be concrete, the checker should warn about all unimplemented
   * methods, but allow clients to instantiate it freely.
   */
  public void test_warnAbstract_onConcreteClassDeclaration_whenHasUnimplementedMethods()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "interface Foo {",
                "  int fooA;",
                "  void fooB();",
                "}",
                "interface Bar {",
                "  void barA();",
                "}",
                "class A implements Foo, Bar {",
                "}",
                "class C {",
                "  foo() {",
                "    return new A();",
                "  }",
                "}"));
    assertErrors(
        libraryResult.getTypeErrors(),
        errEx(TypeErrorCode.ABSTRACT_CLASS_WITHOUT_ABSTRACT_MODIFIER, 8, 7, 1),
        errEx(TypeErrorCode.INSTANTIATION_OF_CLASS_WITH_UNIMPLEMENTED_MEMBERS, 12, 16, 1));
    {
      DartCompilationError typeError = libraryResult.getTypeErrors().get(0);
      String message = typeError.getMessage();
      assertTrue(message.contains("# From Foo:"));
      assertTrue(message.contains("int fooA"));
      assertTrue(message.contains("void fooB()"));
      assertTrue(message.contains("# From Bar:"));
      assertTrue(message.contains("void barA()"));
    }
  }

  /**
   * From specification 0.05, 11/14/2011.
   * <p>
   * In contrast, if A is intended to be concrete, the checker should warn about all unimplemented
   * methods, but allow clients to instantiate it freely.
   */
  public void test_warnAbstract_onConcreteClassDeclaration_whenHasInheritedUnimplementedMethod()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "class A {",
                "  abstract void foo();",
                "}",
                "class B extends A {",
                "}",
                "class C {",
                "  foo() {",
                "    return new B();",
                "  }",
                "}"));
    assertErrors(
        libraryResult.getTypeErrors(),
        errEx(TypeErrorCode.ABSTRACT_CLASS_WITHOUT_ABSTRACT_MODIFIER, 4, 7, 1),
        errEx(TypeErrorCode.INSTANTIATION_OF_CLASS_WITH_UNIMPLEMENTED_MEMBERS, 8, 16, 1));
    {
      DartCompilationError typeError = libraryResult.getTypeErrors().get(0);
      String message = typeError.getMessage();
      assertTrue(message.contains("# From A:"));
      assertTrue(message.contains("void foo()"));
    }
  }

  /**
   * From specification 0.05, 11/14/2011.
   * <p>
   * If A is intended to be abstract, we want the static checker to warn about any attempt to
   * instantiate A, and we do not want the checker to complain about unimplemented methods in A.
   * <p>
   * Here:
   * <ul>
   * <li>"A" has unimplemented methods, but we don't show warnings, because it is explicitly marked
   * as abstract.</li>
   * <li>When we try to create instance of "A", we show warning that it is abstract.</li>
   * </ul>
   */
  public void test_warnAbstract_onAbstractClass_whenInstantiate_normalConstructor()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "interface Foo {",
                "  int fooA;",
                "  void fooB();",
                "}",
                "abstract class A implements Foo {",
                "}",
                "class C {",
                "  foo() {",
                "    return new A();",
                "  }",
                "}"));
    assertErrors(
        libraryResult.getTypeErrors(),
        errEx(TypeErrorCode.INSTANTIATION_OF_ABSTRACT_CLASS, 9, 16, 1));
  }

  /**
   * Variant of {@link #test_warnAbstract_onAbstractClass_whenInstantiate_normalConstructor()}.
   * <p>
   * An abstract class is either a class that is explicitly declared with the abstract modifier, or
   * a class that declares at least one abstract method (7.1.1).
   */
  public void test_warnAbstract_onClassWithAbstractMethod_whenInstantiate_normalConstructor()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "interface Foo {",
                "  void foo();",
                "}",
                "class A implements Foo {",
                "  abstract void bar();",
                "}",
                "class C {",
                "  foo() {",
                "    return new A();",
                "  }",
                "}"));
    assertErrors(
        libraryResult.getTypeErrors(),
        errEx(TypeErrorCode.INSTANTIATION_OF_ABSTRACT_CLASS, 9, 16, 1));
  }

  /**
   * Variant of {@link #test_warnAbstract_onAbstractClass_whenInstantiate_normalConstructor()}.
   * <p>
   * An abstract class is either a class that is explicitly declared with the abstract modifier, or
   * a class that declares at least one abstract method (7.1.1).
   */
  public void test_warnAbstract_onClassWithAbstractGetter_whenInstantiate_normalConstructor()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "interface Foo {",
                "  void foo();",
                "}",
                "class A implements Foo {",
                "  abstract get x();",
                "}",
                "class C {",
                "  foo() {",
                "    return new A();",
                "  }",
                "}"));
    assertErrors(
        libraryResult.getTypeErrors(),
        errEx(TypeErrorCode.INSTANTIATION_OF_ABSTRACT_CLASS, 9, 16, 1));
  }

  /**
   * Factory constructor can instantiate any class and return it non-abstract class instance, but
   * spec requires warnings, so we provide it, but using different constant.
   */
  public void test_warnAbstract_onAbstractClass_whenInstantiate_factoryConstructor()
      throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "abstract class A {",
                "  factory A() {",
                "    return null;",
                "  }",
                "}",
                "class C {",
                "  foo() {",
                "    return new A();",
                "  }",
                "}"));
    assertErrors(
        libraryResult.getTypeErrors(),
        errEx(TypeErrorCode.INSTANTIATION_OF_ABSTRACT_CLASS_USING_FACTORY, 8, 16, 1));
  }

  /**
   * Spec 7.3 It is a static warning if a setter declares a return type other than void.
   */
  public void testWarnOnNonVoidSetter() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "class A {",
                "  void set foo(bool a) {}",
                "  set bar(bool a) {}",
                "  Dynamic set baz(bool a) {}",
                "  bool set bob(bool a) {}",
                "}"));
    assertErrors(
        libraryResult.getTypeErrors(),
        errEx(TypeErrorCode.SETTER_RETURN_TYPE, 4, 3, 7),
        errEx(TypeErrorCode.SETTER_RETURN_TYPE, 5, 3, 4));
  }

  /**
   * We should be able to call <code>Function</code> even if it is in the field.
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=933
   */
  public void test_callFunctionFromField() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "class WorkElement {",
                "  Function run;",
                "}",
                "foo(WorkElement e) {",
                "  e.run();",
                "}"));
    assertErrors(libraryResult.getTypeErrors());
  }

  /**
   * Test for errors and warnings related to positional and named arguments for required and
   * optional parameters.
   */
  public void test_invocationArguments() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "/* 01 */ foo() {",
                "/* 02 */   f_0_0();",
                "/* 03 */   f_0_0(-1);",
                "/* 04 */",
                "/* 05 */   f_1_0();",
                "/* 06 */   f_1_0(-1);",
                "/* 07 */   f_1_0(-1, -2, -3);",
                "/* 08 */",
                "/* 09 */   f_2_0();",
                "/* 10 */",
                "/* 11 */   f_0_1();",
                "/* 12 */   f_0_1(1);",
                "/* 13 */   f_0_1(0, 0);",
                "/* 14 */   f_0_1(n1: 1);",
                "/* 15 */   f_0_1(x: 1);",
                "/* 16 */   f_0_1(n1: 1, n1: 2);",
                "/* 17 */",
                "/* 18 */   f_1_3(-1, 1, n3: 2);",
                "/* 19 */   f_1_3(-1, 1, n1: 1);",
                "}",
                "",
                "f_0_0() {}",
                "f_1_0(r1) {}",
                "f_2_0(r1, r2) {}",
                "f_0_1([n1]) {}",
                "f_0_2([n1, n2]) {}",
                "f_1_3(r1, [n1, n2, n3]) {}",
                ""));
    assertErrors(
        libraryResult.getTypeErrors(),
        errEx(TypeErrorCode.EXTRA_ARGUMENT, 3, 18, 2),
        errEx(TypeErrorCode.MISSING_ARGUMENT, 5, 12, 7),
        errEx(TypeErrorCode.EXTRA_ARGUMENT, 7, 22, 2),
        errEx(TypeErrorCode.EXTRA_ARGUMENT, 7, 26, 2),
        errEx(TypeErrorCode.MISSING_ARGUMENT, 9, 12, 7),
        errEx(TypeErrorCode.EXTRA_ARGUMENT, 13, 21, 1),
        errEx(TypeErrorCode.NO_SUCH_NAMED_PARAMETER, 15, 18, 4),
        errEx(TypeErrorCode.DUPLICATE_NAMED_ARGUMENT, 19, 25, 5));
    assertErrors(
        libraryResult.getCompilationErrors(),
        errEx(ResolverErrorCode.DUPLICATE_NAMED_ARGUMENT, 16, 25, 5));
  }

  /**
   * We should return correct {@link Type} for {@link DartNewExpression}.
   */
  public void test_DartNewExpression_getType() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "// filler filler filler filler filler filler filler filler filler filler",
                "class A {",
                "  A() {}",
                "  A.foo() {}",
                "}",
                "var a1 = new A();",
                "var a2 = new A.foo();",
                ""));
    assertErrors(libraryResult.getCompilationErrors());
    assertErrors(libraryResult.getCompilationWarnings());
    assertErrors(libraryResult.getTypeErrors());
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnit(getName());
    // new A()
    {
      DartNewExpression newExpression = (DartNewExpression) getTopLevelFieldInitializer(unit, 1);
      Type newType = newExpression.getType();
      assertEquals("A", newType.getElement().getName());
    }
    // new A.foo()
    {
      DartNewExpression newExpression = (DartNewExpression) getTopLevelFieldInitializer(unit, 2);
      Type newType = newExpression.getType();
      assertEquals("A", newType.getElement().getName());
    }
  }

  /**
   * Expects that given {@link DartUnit} has {@link DartFieldDefinition} as <code>index</code> top
   * level node and return initializer of first {@link DartField}.
   */
  private static DartExpression getTopLevelFieldInitializer(DartUnit unit, int index) {
    DartFieldDefinition fieldDefinition = (DartFieldDefinition) unit.getTopLevelNodes().get(index);
    DartField field = fieldDefinition.getFields().get(0);
    return field.getValue();
  }

  /**
   * If property has only setter, no getter, then attempt to use getter should cause static type
   * warning.
   * <p>
   * http://code.google.com/p/dart/issues/detail?id=1251
   */
  public void test_setterOnlyProperty_noGetter() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "class SetOnly {",
                "  set foo(arg) {}",
                "}",
                "class SetOnlyWrapper {",
                "  SetOnly setOnly;",
                "}",
                "",
                "main() {",
                "  SetOnly setOnly = new SetOnly();",
                "  setOnly.foo = 1;", // 10: OK, use setter
                "  setOnly.foo += 2;", // 11: ERR, no getter
                "  print(setOnly.foo);", // 12: ERR, no getter
                "  var bar;",
                "  bar = setOnly.foo;", // 14: ERR, assignment, but we are not LHS
                "  bar = new SetOnlyWrapper().setOnly.foo;", // 15: ERR, even in chained expression
                "  new SetOnlyWrapper().setOnly.foo = 3;", // 16: OK
                "}"));
    assertErrors(
        libraryResult.getTypeErrors(),
        errEx(TypeErrorCode.FIELD_HAS_NO_GETTER, 11, 11, 3),
        errEx(TypeErrorCode.FIELD_HAS_NO_GETTER, 12, 17, 3),
        errEx(TypeErrorCode.FIELD_HAS_NO_GETTER, 14, 17, 3),
        errEx(TypeErrorCode.FIELD_HAS_NO_GETTER, 15, 38, 3));
  }

  public void test_setterOnlyProperty_normalField() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "class A {",
                "  var foo;",
                "}",
                "",
                "main() {",
                "  A a = new A();",
                "  a.foo = 1;",
                "  a.foo += 2;",
                "  print(a.foo);",
                "}"));
    assertErrors(libraryResult.getTypeErrors());
  }

  public void test_setterOnlyProperty_getterInSuper() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "class A {",
                "  get foo() {}",
                "}",
                "class B extends A {",
                "  set foo(arg) {}",
                "}",
                "",
                "main() {",
                "  B b = new B();",
                "  b.foo = 1;",
                "  b.foo += 2;",
                "  print(b.foo);",
                "}"));
    assertErrors(libraryResult.getTypeErrors());
  }

  public void test_setterOnlyProperty_getterInInterface() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "interface A {",
                "  get foo() {}",
                "}",
                "class B implements A {",
                "  set foo(arg) {}",
                "}",
                "",
                "main() {",
                "  B b = new B();",
                "  b.foo = 1;",
                "  b.foo += 2;",
                "  print(b.foo);",
                "}"));
    assertErrors(libraryResult.getTypeErrors());
  }

  public void test_getterOnlyProperty_noSetter() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "class GetOnly {",
                "  get foo() {}",
                "}",
                "class GetOnlyWrapper {",
                "  GetOnly getOnly;",
                "}",
                "",
                "main() {",
                "  GetOnly getOnly = new GetOnly();",
                "  print(getOnly.foo);", // 10: OK, use getter
                "  getOnly.foo = 1;", // 11: ERR, no setter
                "  getOnly.foo += 2;", // 12: ERR, no setter
                "  var bar;",
                "  bar = getOnly.foo;", // 14: OK, use getter
                "  new GetOnlyWrapper().getOnly.foo = 3;", // 15: ERR, no setter
                "  bar = new GetOnlyWrapper().getOnly.foo;", // 16: OK, use getter
                "}"));
    assertErrors(
        libraryResult.getTypeErrors(),
        errEx(TypeErrorCode.FIELD_HAS_NO_SETTER, 11, 11, 3),
        errEx(TypeErrorCode.FIELD_HAS_NO_SETTER, 12, 11, 3),
        errEx(TypeErrorCode.FIELD_HAS_NO_SETTER, 15, 32, 3));
  }

  public void test_getterOnlyProperty_setterInSuper() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "class A {",
                "  set foo(arg) {}",
                "}",
                "class B extends A {",
                "  get foo() {}",
                "}",
                "",
                "main() {",
                "  B b = new B();",
                "  b.foo = 1;",
                "  b.foo += 2;",
                "  print(b.foo);",
                "}"));
    assertErrors(libraryResult.getTypeErrors());
  }

  public void test_getterOnlyProperty_setterInInterface() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "interface A {",
                "  set foo(arg) {}",
                "}",
                "class B implements A {",
                "  get foo() {}",
                "}",
                "",
                "main() {",
                "  B b = new B();",
                "  b.foo = 1;",
                "  b.foo += 2;",
                "  print(b.foo);",
                "}"));
    assertErrors(libraryResult.getTypeErrors());
  }

  public void test_bindToLibraryFunctionFirst() throws Exception {
    AnalyzeLibraryResult libraryResult =
        analyzeLibrary(
            getName(),
            makeCode(
                "// filler filler filler filler filler filler filler filler filler filler",
                "foo() {}",
                "class A {",
                " foo() {}",
                "}",
                "class B extends A {",
                "  bar() {",
                "    foo();",
                "  }",
                "}",
                ""));
    DartUnit unit = libraryResult.getLibraryUnitResult().getUnit(getName());
    // Find foo() invocation.
    DartUnqualifiedInvocation invocation;
    {
      DartClass classB = (DartClass) unit.getTopLevelNodes().get(2);
      DartMethodDefinition methodBar = (DartMethodDefinition) classB.getMembers().get(0);
      DartExprStmt stmt = (DartExprStmt) methodBar.getFunction().getBody().getStatements().get(0);
      invocation = (DartUnqualifiedInvocation) stmt.getExpression();
    }
    // Check that unqualified foo() invocation is resolved to the top-level (library) function. 
    Symbol symbol = invocation.getTarget().getSymbol();
    assertNotNull(symbol);
    assertSame(unit, symbol.getNode().getParent());
  }

  /**
   * If there was <code>#import</code> with invalid {@link URI}, it should be reported as error, not
   * as an exception.
   */
  public void test_invalidImportUri() throws Exception {
    List<DartCompilationError> errors =
        analyzeLibrarySourceErrors(makeCode(
            "// filler filler filler filler filler filler filler filler filler filler",
            "#library('test');",
            "#import('badURI');",
            ""));
    assertErrors(errors, errEx(DartCompilerErrorCode.MISSING_SOURCE, 3, 1, 18));
  }

  /**
   * If there was <code>#source</code> with invalid {@link URI}, it should be reported as error, not
   * as an exception.
   */
  public void test_invalidSourceUri() throws Exception {
    List<DartCompilationError> errors =
        analyzeLibrarySourceErrors(makeCode(
            "// filler filler filler filler filler filler filler filler filler filler",
            "#library('test');",
            "#source('badURI');",
            ""));
    assertErrors(errors, errEx(DartCompilerErrorCode.MISSING_SOURCE, 3, 1, 18));
  }

  /**
   * Analyzes source for given library and returns {@link DartCompilationError}s.
   */
  private static List<DartCompilationError> analyzeLibrarySourceErrors(final String code)
      throws Exception {
    MockLibrarySource lib = new MockLibrarySource() {
      @Override
      public Reader getSourceReader() {
        return new StringReader(code);
      }
    };
    DartArtifactProvider provider = new MockArtifactProvider();
    final List<DartCompilationError> errors = Lists.newArrayList();
    DartCompiler.analyzeLibrary(
        lib,
        Maps.<URI, DartUnit>newHashMap(),
        CHECK_ONLY_CONFIGURATION,
        provider,
        new DartCompilerListener.Empty() {
          @Override
          public void onError(DartCompilationError event) {
            errors.add(event);
          }
        });
    return errors;
  }

  public void test_mapLiteralKeysUnique() throws Exception {
    List<DartCompilationError> errors =
        analyzeLibrarySourceErrors(makeCode(
            "// filler filler filler filler filler filler filler filler filler filler",
            "var m = {'a' : 0, 'b': 1, 'a': 2};",
            ""));
    assertErrors(errors, errEx(TypeErrorCode.MAP_LITERAL_KEY_UNIQUE, 2, 27, 3));
  }

  /**
   * No required parameter "x".
   */
  public void test_implementsAndOverrides_noRequiredParameter() throws Exception {
    AnalyzeLibraryResult result =
        analyzeLibrary(
            "interface I {",
            "  foo(x);",
            "}",
            "class C implements I {",
            "  foo() {}",
            "}");
    assertErrors(
        result.getErrors(),
        errEx(ResolverErrorCode.CANNOT_OVERRIDE_METHOD_NUM_REQUIRED_PARAMS, 5, 3, 3));
  }

  /**
   * It is OK to add more named parameters, if list prefix is same as in "super".
   */
  public void test_implementsAndOverrides_additionalNamedParameter() throws Exception {
    AnalyzeLibraryResult result =
        analyzeLibrary(
            "interface I {",
            "  foo([x]);",
            "}",
            "class C implements I {",
            "  foo([x,y]) {}",
            "}");
    assertErrors(result.getErrors());
  }

  /**
   * We override "foo" with method that has named parameter. So, this method is not abstract and
   * class is not abstract too, so no warning.
   */
  public void test_implementsAndOverrides_additionalNamedParameter_notAbstract() throws Exception {
    AnalyzeLibraryResult result =
        analyzeLibrary(
            "class A {",
            "  abstract foo();",
            "}",
            "class B extends A {",
            "  foo([x]) {}",
            "}",
            "bar() {",
            "  new B();",
            "}",
            "");
    assertErrors(result.getErrors());
  }

  /**
   * No required parameter "x". Named parameter "x" is not enough.
   */
  public void test_implementsAndOverrides_extraRequiredParameter() throws Exception {
    AnalyzeLibraryResult result =
        analyzeLibrary(
            "interface I {",
            "  foo();",
            "}",
            "class C implements I {",
            "  foo(x) {}",
            "}");
    assertErrors(
        result.getErrors(),
        errEx(ResolverErrorCode.CANNOT_OVERRIDE_METHOD_NUM_REQUIRED_PARAMS, 5, 3, 3));
  }

  /**
   * It is a compile-time error if an instance method m1 overrides an instance member m2 and m1 does
   * not declare all the named parameters declared by m2 in the same order.
   * <p>
   * Here: no "y" parameter.
   */
  public void test_implementsAndOverrides_noNamedParameter() throws Exception {
    AnalyzeLibraryResult result =
        analyzeLibrary(
            "interface I {",
            "  foo([x,y]);",
            "}",
            "class C implements I {",
            "  foo([x]) {}",
            "}");
    assertErrors(
        result.getErrors(),
        errEx(ResolverErrorCode.CANNOT_OVERRIDE_METHOD_NAMED_PARAMS, 5, 3, 3));
  }

  /**
   * It is a compile-time error if an instance method m1 overrides an instance member m2 and m1 does
   * not declare all the named parameters declared by m2 in the same order.
   * <p>
   * Here: wrong order.
   */
  public void testImplementsAndOverrides5() throws Exception {
    AnalyzeLibraryResult result =
        analyzeLibrary(
            "interface I {",
            "  foo([y,x]);",
            "}",
            "class C implements I {",
            "  foo([x,y]) {}",
            "}");
    assertErrors(
        result.getErrors(),
        errEx(ResolverErrorCode.CANNOT_OVERRIDE_METHOD_NAMED_PARAMS, 5, 3, 3));
  }

  private AnalyzeLibraryResult analyzeLibrary(String... lines) throws Exception {
    return analyzeLibrary(getName(), makeCode(lines));
  }
}
