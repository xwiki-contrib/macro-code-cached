/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.codecache.internal;

import java.util.Collections;

import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.MacroBlock;
import org.xwiki.rendering.block.MetaDataBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.listener.MetaData;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.rendering.transformation.MacroTransformationContext;
import org.xwiki.rendering.transformation.TransformationContext;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CodeCachedBaseReferenceResolver}.
 *
 * @version $Id$
 */
@ComponentTest
class CodeCachedBaseReferenceResolverTest
{
    private static final DocumentReference CURRENT_DOCUMENT = new DocumentReference("wiki", "Space", "Page");

    private static final DocumentReference INCLUDED_DOCUMENT = new DocumentReference("wiki", "Included", "Page");

    private static final DocumentReference NESTED_DOCUMENT = new DocumentReference("wiki", "Included", "Nested");

    @MockComponent
    @Named("current")
    private DocumentReferenceResolver<String> currentDocumentReferenceResolver;

    @MockComponent
    private EntityReferenceSerializer<String> serializer;

    @InjectMockComponents
    private CodeCachedBaseReferenceResolver resolver;

    @BeforeEach
    void setUp()
    {
        when(this.currentDocumentReferenceResolver.resolve("")).thenReturn(CURRENT_DOCUMENT);
        when(this.currentDocumentReferenceResolver.resolve("Included.Page", CURRENT_DOCUMENT))
            .thenReturn(INCLUDED_DOCUMENT);
        when(this.currentDocumentReferenceResolver.resolve("Nested", INCLUDED_DOCUMENT)).thenReturn(NESTED_DOCUMENT);
        when(this.serializer.serialize(any())).thenAnswer(invocation -> invocation.getArgument(0).toString());
    }

    @Test
    void resolveCurrentDocument()
    {
        assertEquals(CURRENT_DOCUMENT.toString(), this.resolver.resolve(createContext(new MacroBlock("code",
            Collections.emptyMap(), "content", false))));
    }

    @Test
    void resolveIncludedDocument()
    {
        // The base references around the macro are resolved from the outer one, each relative to the previous one,
        // e.g. for a macro located in a document included by an included document.
        MacroBlock macroBlock = new MacroBlock("code", Collections.emptyMap(), "content", false);
        Block nestedBlock = new MetaDataBlock(Collections.singletonList(macroBlock), MetaData.BASE, "Nested");
        Block includedBlock =
            new MetaDataBlock(Collections.singletonList(nestedBlock), MetaData.BASE, "Included.Page");
        new XDOM(Collections.singletonList(includedBlock));

        assertEquals(NESTED_DOCUMENT.toString(), this.resolver.resolve(createContext(macroBlock)));
    }

    @Test
    void resolveWithoutMacroBlock()
    {
        assertEquals(CURRENT_DOCUMENT.toString(), this.resolver.resolve(createContext(null)));
    }

    private MacroTransformationContext createContext(MacroBlock macroBlock)
    {
        MacroTransformationContext context = new MacroTransformationContext(
            new TransformationContext(new XDOM(Collections.emptyList()), Syntax.XWIKI_2_1));
        context.setCurrentMacroBlock(macroBlock);
        return context;
    }
}
