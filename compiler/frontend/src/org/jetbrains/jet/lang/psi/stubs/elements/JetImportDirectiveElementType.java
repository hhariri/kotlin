/*
 * Copyright 2010-2014 JetBrains s.r.o.
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

package org.jetbrains.jet.lang.psi.stubs.elements;

import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.stubs.StubInputStream;
import com.intellij.psi.stubs.StubOutputStream;
import com.intellij.util.io.StringRef;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.psi.JetImportDirective;
import org.jetbrains.jet.lang.psi.stubs.PsiJetImportDirectiveStub;
import org.jetbrains.jet.lang.psi.stubs.impl.PsiJetImportDirectiveStubImpl;

import java.io.IOException;

public class JetImportDirectiveElementType extends JetStubElementType<PsiJetImportDirectiveStub, JetImportDirective> {
    public JetImportDirectiveElementType(@NotNull @NonNls String debugName) {
        super(debugName, JetImportDirective.class, PsiJetImportDirectiveStub.class);
    }

    @Override
    public PsiJetImportDirectiveStub createStub(@NotNull JetImportDirective psi, StubElement parentStub) {
        StringRef aliasName = StringRef.fromString(psi.getAliasName());
        return new PsiJetImportDirectiveStubImpl(parentStub, psi.isAbsoluteInRootPackage(), psi.isAllUnder(),
                                                 aliasName, psi.isValidImport());
    }

    @Override
    public void serialize(@NotNull PsiJetImportDirectiveStub stub, @NotNull StubOutputStream dataStream) throws IOException {
        dataStream.writeBoolean(stub.isAbsoluteInRootPackage());
        dataStream.writeBoolean(stub.isAllUnder());
        dataStream.writeName(stub.getAliasName());
        dataStream.writeBoolean(stub.isValid());
    }

    @NotNull
    @Override
    public PsiJetImportDirectiveStub deserialize(@NotNull StubInputStream dataStream, StubElement parentStub) throws IOException {
        boolean isAbsoluteInRootPackage = dataStream.readBoolean();
        boolean isAllUnder = dataStream.readBoolean();
        StringRef aliasName = dataStream.readName();
        boolean isValid = dataStream.readBoolean();
        return new PsiJetImportDirectiveStubImpl(parentStub, isAbsoluteInRootPackage, isAllUnder, aliasName, isValid);
    }
}
