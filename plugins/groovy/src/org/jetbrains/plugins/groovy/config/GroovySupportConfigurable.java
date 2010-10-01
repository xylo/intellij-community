/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package org.jetbrains.plugins.groovy.config;

import com.intellij.ide.util.frameworkSupport.FrameworkSupportConfigurable;
import com.intellij.ide.util.frameworkSupport.FrameworkVersion;
import com.intellij.ide.util.frameworkSupport.FrameworkVersionWithLibrary;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.libraries.Library;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
* @author peter
*/
public class GroovySupportConfigurable extends FrameworkSupportConfigurable {
  private FrameworkVersionWithLibrary myVersion;

  public GroovySupportConfigurable() {
  }

  @Override
  public FrameworkVersion getSelectedVersion() {
    if (myVersion == null) {
      myVersion = new FrameworkVersionWithLibrary("", true, new GroovyLibraryDescription());
    }
    return myVersion;
  }

  public JComponent getComponent() {
    return null;
  }

  public void addSupport(@NotNull final Module module, @NotNull final ModifiableRootModel rootModel, @Nullable Library library) {
  }
}
