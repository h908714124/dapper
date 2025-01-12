/*
 * Copyright (C) 2015 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen.langmodel;

import static dagger.internal.codegen.langmodel.Accessibility.isElementAccessibleFrom;
import static io.jbock.common.truth.Truth.assertThat;

import io.jbock.testing.compile.CompilationExtension;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(CompilationExtension.class)
@SuppressWarnings("unused") // contains a variety things used by the compilation rule for testing
public class AccessibilityTest {
  /* test data */
  public AccessibilityTest() {
  }

  public String publicField;
  protected String protectedField;
  String packagePrivateField;
  private String privateField;

  public void publicMethod() {
  }

  protected void protectedMethod() {
  }

  void packagePrivateMethod() {
  }

  private void privateMethod() {
  }

  public static final class PublicNestedClass {
  }

  protected static final class ProtectedNestedClass {
  }

  static final class PackagePrivateNestedClass {
  }

  private static final class PrivateNestedClass {
  }

  private TypeElement testElement;

  @BeforeEach
  public void setUp(Elements elements) {
    testElement = elements.getTypeElement(AccessibilityTest.class.getCanonicalName());
  }

  @Test
  public void isElementAccessibleFrom_publicType() {
    assertThat(isElementAccessibleFrom(testElement, "literally.anything")).isTrue();
  }

  @Test
  public void isElementAccessibleFrom_publicMethod() {
    Element member = getMemberNamed("publicMethod");
    assertThat(isElementAccessibleFrom(member, "literally.anything")).isTrue();
  }

  @Test
  public void isElementAccessibleFrom_protectedMethod() {
    Element member = getMemberNamed("protectedMethod");
    assertThat(isElementAccessibleFrom(member, "dagger.internal.codegen.langmodel")).isTrue();
    assertThat(isElementAccessibleFrom(member, "not.dagger.internal.codegen")).isFalse();
  }

  @Test
  public void isElementAccessibleFrom_packagePrivateMethod() {
    Element member = getMemberNamed("packagePrivateMethod");
    assertThat(isElementAccessibleFrom(member, "dagger.internal.codegen.langmodel")).isTrue();
    assertThat(isElementAccessibleFrom(member, "not.dagger.internal.codegen")).isFalse();
  }

  @Test
  public void isElementAccessibleFrom_privateMethod() {
    Element member = getMemberNamed("privateMethod");
    assertThat(isElementAccessibleFrom(member, "dagger.internal.codegen")).isFalse();
    assertThat(isElementAccessibleFrom(member, "not.dagger.internal.codegen")).isFalse();
  }

  @Test
  public void isElementAccessibleFrom_publicField() {
    Element member = getMemberNamed("publicField");
    assertThat(isElementAccessibleFrom(member, "literally.anything")).isTrue();
  }

  @Test
  public void isElementAccessibleFrom_protectedField() {
    Element member = getMemberNamed("protectedField");
    assertThat(isElementAccessibleFrom(member, "dagger.internal.codegen.langmodel")).isTrue();
    assertThat(isElementAccessibleFrom(member, "not.dagger.internal.codegen")).isFalse();
  }

  @Test
  public void isElementAccessibleFrom_packagePrivateField() {
    Element member = getMemberNamed("packagePrivateField");
    assertThat(isElementAccessibleFrom(member, "dagger.internal.codegen.langmodel")).isTrue();
    assertThat(isElementAccessibleFrom(member, "not.dagger.internal.codegen")).isFalse();
  }

  @Test
  public void isElementAccessibleFrom_privateField() {
    Element member = getMemberNamed("privateField");
    assertThat(isElementAccessibleFrom(member, "dagger.internal.codegen")).isFalse();
    assertThat(isElementAccessibleFrom(member, "not.dagger.internal.codegen")).isFalse();
  }

  private Element getMemberNamed(String memberName) {
    for (Element enclosedElement : testElement.getEnclosedElements()) {
      if (enclosedElement.getSimpleName().contentEquals(memberName)) {
        return enclosedElement;
      }
    }
    throw new IllegalArgumentException();
  }
}

