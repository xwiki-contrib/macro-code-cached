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
import java.util.List;

import javax.inject.Named;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.model.ModelContext;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.rendering.RenderingException;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.MacroBlock;
import org.xwiki.rendering.block.MetaDataBlock;
import org.xwiki.rendering.block.ParagraphBlock;
import org.xwiki.rendering.block.WordBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.listener.MetaData;
import org.xwiki.rendering.listener.reference.ResourceReference;
import org.xwiki.rendering.listener.reference.ResourceType;
import org.xwiki.rendering.macro.code.CodeMacroParameters;
import org.xwiki.rendering.macro.code.source.CodeMacroSource;
import org.xwiki.rendering.macro.code.source.CodeMacroSourceFactory;
import org.xwiki.rendering.macro.source.MacroContentSourceReference;
import org.xwiki.rendering.renderer.BlockRenderer;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.rendering.transformation.MacroTransformationContext;
import org.xwiki.rendering.transformation.TransformationContext;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CodeCachedBlockAsyncRenderer}.
 *
 * @version $Id$
 */
@ComponentTest
class CodeCachedBlockAsyncRendererTest
{
    private static final String CONTENT = "public class Foo {}";

    private static final String OTHER_CONTENT = "public class Bar {}";

    private static final String PAGE = "Space.Page";

    private static final String BASE = "wiki:Space.Page";

    private static final MacroContentSourceReference SCRIPT_SOURCE =
        new MacroContentSourceReference(MacroContentSourceReference.TYPE_SCRIPT, "code");

    @MockComponent
    private CodeMacroSourceFactory sourceFactory;

    @MockComponent
    private ModelContext modelContext;

    @MockComponent
    private CodeCachedBaseReferenceResolver baseReferenceResolver;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    @Named("context")
    private ComponentManager contextComponentManager;

    @InjectMockComponents
    private CodeCachedBlockAsyncRenderer renderer;

    private final CodeCachedMacro codeMacro = mock(CodeCachedMacro.class);

    private final DocumentReference contextDocument = new DocumentReference("wiki", "Space", "Page");

    @Test
    void idDependsOnContentAndNotOnPosition() throws Exception
    {
        // Two different code macros found at the same position of the same document, e.g. because the result of a
        // script macro placed before them changed between two renderings.
        List<String> id1 = getId(createContext(PAGE, 1, CONTENT), CONTENT, javaParameters());
        List<String> id2 = getId(createContext(PAGE, 1, OTHER_CONTENT), OTHER_CONTENT, javaParameters());

        assertNotEquals(id1, id2);
    }

    @Test
    void idIsSharedBySameContentAndParametersInDifferentDocuments() throws Exception
    {
        List<String> id1 = getId(createContext(PAGE, 1, CONTENT), CONTENT, javaParameters());
        List<String> id2 = getId(createContext("Other.Page", 3, CONTENT), CONTENT, javaParameters());

        assertEquals(id1, id2);
        verifyNoInteractions(this.baseReferenceResolver);
    }

    @Test
    void idDependsOnParameters() throws Exception
    {
        CodeMacroParameters pythonParameters = new CodeMacroParameters();
        pythonParameters.setLanguage("python");

        assertNotEquals(getId(createContext(PAGE, 1, CONTENT), CONTENT, javaParameters()),
            getId(createContext(PAGE, 1, CONTENT), CONTENT, pythonParameters));
    }

    @Test
    void idDependsOnMacroSyntax() throws Exception
    {
        // The title is parsed with the syntax of the macro.
        CodeMacroParameters parameters = javaParameters();
        parameters.setTitle("**title**");
        MacroTransformationContext xwiki20Context = createContext(PAGE, 1, CONTENT);
        xwiki20Context.setSyntax(Syntax.XWIKI_2_0);

        assertNotEquals(getId(createContext(PAGE, 1, CONTENT), CONTENT, parameters),
            getId(xwiki20Context, CONTENT, parameters));
    }

    @Test
    void idDependsOnWiki() throws Exception
    {
        when(this.modelContext.getCurrentEntityReference()).thenReturn(new WikiReference("wiki1"));
        List<String> id1 = getId(createContext(PAGE, 1, CONTENT), CONTENT, javaParameters());

        when(this.modelContext.getCurrentEntityReference()).thenReturn(new WikiReference("wiki2"));
        List<String> id2 = getId(createContext(PAGE, 1, CONTENT), CONTENT, javaParameters());

        assertNotEquals(id1, id2);
    }

    @Test
    void idDependsOnSourceContent() throws Exception
    {
        CodeMacroParameters parameters = javaParameters();
        parameters.setSource(SCRIPT_SOURCE);

        when(this.sourceFactory.getContent(any(), any())).thenReturn(new CodeMacroSource(SCRIPT_SOURCE, CONTENT, null));
        List<String> id1 = getId(createContext(PAGE, 1, ""), "", parameters);

        when(this.sourceFactory.getContent(any(), any()))
            .thenReturn(new CodeMacroSource(SCRIPT_SOURCE, OTHER_CONTENT, null));
        List<String> id2 = getId(createContext(PAGE, 1, ""), "", parameters);

        assertNotEquals(id1, id2);
    }

    @Test
    void executeHighlightsSourceContentResolvedAtInitialization() throws Exception
    {
        // The source is resolved in the context of the request (e.g. its script context), and the code macro, which
        // can be executed later on, asynchronously, highlights that resolved content.
        CodeMacroParameters parameters = new CodeMacroParameters();
        parameters.setSource(SCRIPT_SOURCE);
        when(this.sourceFactory.getContent(any(), any()))
            .thenReturn(new CodeMacroSource(SCRIPT_SOURCE, CONTENT, "java"));
        List<Block> highlighted = Collections.singletonList(new WordBlock("highlighted"));
        when(this.codeMacro.executeCodeMacro(argThat(p -> p.getSource() == null && "java".equals(p.getLanguage())),
            eq(CONTENT), any())).thenReturn(highlighted);

        getId(createContext(PAGE, 1, ""), "", parameters);

        assertEquals(highlighted, this.renderer.execute(true, false).getChildren());
    }

    @Test
    void idDependsOnDocumentWhenTitleOrImageIsSet() throws Exception
    {
        // The references found in the title and in the image are relative to the document containing the macro.
        CodeMacroParameters titleParameters = javaParameters();
        titleParameters.setTitle("[[Child]]");
        CodeMacroParameters imageParameters = javaParameters();
        imageParameters.setImage(new ResourceReference("image.png", ResourceType.URL));

        for (CodeMacroParameters parameters : List.of(titleParameters, imageParameters)) {
            when(this.baseReferenceResolver.resolve(any())).thenReturn(BASE);
            List<String> id1 = getId(createContext(PAGE, 1, CONTENT), CONTENT, parameters);
            when(this.baseReferenceResolver.resolve(any())).thenReturn("wiki:Other.Page");
            List<String> id2 = getId(createContext(PAGE, 1, CONTENT), CONTENT, parameters);

            assertNotEquals(id1, id2);
        }
    }

    @Test
    void executeIndicatesDocumentContainingMacroWhenTitleIsSet() throws Exception
    {
        // When the result is computed asynchronously, it's rendered outside of the document containing the macro, so
        // the result must indicate which document the references are relative to.
        CodeMacroParameters parameters = javaParameters();
        parameters.setTitle("[[Child]]");
        when(this.baseReferenceResolver.resolve(any())).thenReturn(BASE);
        List<Block> highlighted = Collections.singletonList(new WordBlock("highlighted"));
        when(this.codeMacro.executeCodeMacro(any(), eq(CONTENT), any())).thenReturn(highlighted);

        getId(createContext(PAGE, 1, CONTENT), CONTENT, parameters);

        assertEquals(List.of(new MetaDataBlock(highlighted, MetaData.BASE, BASE)),
            this.renderer.execute(true, false).getChildren());
    }

    @Test
    void executeDoesNotIndicateDocumentWithoutTitleOrImage() throws Exception
    {
        // Without references to resolve, the result is shared by the code macros of all the documents.
        when(this.baseReferenceResolver.resolve(any())).thenReturn(BASE);
        List<Block> highlighted = Collections.singletonList(new WordBlock("highlighted"));
        when(this.codeMacro.executeCodeMacro(any(), eq(CONTENT), any())).thenReturn(highlighted);

        getId(createContext(PAGE, 1, CONTENT), CONTENT, javaParameters());

        assertEquals(highlighted, this.renderer.execute(true, false).getChildren());
        verifyNoInteractions(this.baseReferenceResolver);
    }

    @Test
    void executeDoesNotIndicateDocumentInInlineMode() throws Exception
    {
        // The title and the image are not displayed in inline mode.
        CodeMacroParameters parameters = javaParameters();
        parameters.setTitle("[[Child]]");
        when(this.baseReferenceResolver.resolve(any())).thenReturn(BASE);
        List<Block> highlighted = Collections.singletonList(new WordBlock("highlighted"));
        when(this.codeMacro.executeCodeMacro(any(), eq(CONTENT), any())).thenReturn(highlighted);
        MacroTransformationContext context = createContext(PAGE, 1, CONTENT);
        context.setInline(true);

        getId(context, CONTENT, parameters);

        assertEquals(highlighted, this.renderer.execute(true, false).getChildren());
        verifyNoInteractions(this.baseReferenceResolver);
    }

    @Test
    void idDoesNotDependOnContextDocument() throws Exception
    {
        // A page included in several pages has the same base reference but a different context document in each.
        CodeMacroParameters parameters = javaParameters();
        parameters.setTitle("[[Child]]");
        when(this.baseReferenceResolver.resolve(any())).thenReturn(BASE);

        when(this.documentAccessBridge.getCurrentDocumentReference()).thenReturn(this.contextDocument);
        List<String> id1 = getId(createContext(PAGE, 1, CONTENT), CONTENT, parameters);
        when(this.documentAccessBridge.getCurrentDocumentReference())
            .thenReturn(new DocumentReference("wiki", "Space", "Includer"));
        List<String> id2 = getId(createContext(PAGE, 1, CONTENT), CONTENT, parameters);

        assertEquals(id1, id2);
    }

    @Test
    void renderAsynchronouslyInContextDocumentWhenTitleIsSet() throws Exception
    {
        // Some URLs generated for the title references depend on the context document (e.g. the parent of the page to
        // create for a link to a missing page), while the asynchronous rendering happens outside of that document.
        CodeMacroParameters parameters = javaParameters();
        parameters.setTitle("[[Missing]]");
        when(this.baseReferenceResolver.resolve(any())).thenReturn(BASE);
        when(this.documentAccessBridge.getCurrentDocumentReference()).thenReturn(this.contextDocument);
        when(this.codeMacro.executeCodeMacro(any(), eq(CONTENT), any()))
            .thenReturn(Collections.singletonList(new WordBlock("highlighted")));
        BlockRenderer blockRenderer = mockBlockRenderer();

        getId(createContext(PAGE, 1, CONTENT), CONTENT, parameters);
        this.renderer.render(true, true);

        InOrder inOrder = inOrder(this.documentAccessBridge, this.codeMacro, blockRenderer);
        inOrder.verify(this.documentAccessBridge).pushDocumentInContext(any(), eq(this.contextDocument));
        inOrder.verify(this.codeMacro).executeCodeMacro(any(), eq(CONTENT), any());
        inOrder.verify(blockRenderer).render(any(Block.class), any());
        inOrder.verify(this.documentAccessBridge).popDocumentFromContext(any());
    }

    @Test
    void renderAsynchronouslyFailsWhenContextDocumentCannotBeSet() throws Exception
    {
        CodeMacroParameters parameters = javaParameters();
        parameters.setTitle("[[Missing]]");
        when(this.baseReferenceResolver.resolve(any())).thenReturn(BASE);
        when(this.documentAccessBridge.getCurrentDocumentReference()).thenReturn(this.contextDocument);
        Exception cause = new Exception("store error");
        doThrow(cause).when(this.documentAccessBridge).pushDocumentInContext(any(), eq(this.contextDocument));

        getId(createContext(PAGE, 1, CONTENT), CONTENT, parameters);
        RenderingException exception = assertThrows(RenderingException.class, () -> this.renderer.render(true, true));

        assertEquals("Failed to set the context document [wiki:Space.Page]", exception.getMessage());
        assertSame(cause, exception.getCause());
        // The previous context document was not replaced, so there's nothing to restore.
        verify(this.documentAccessBridge, never()).popDocumentFromContext(any());
        verifyNoInteractions(this.codeMacro);
    }

    @Test
    void renderAsynchronouslyWithoutContextDocumentWithoutTitleOrImage() throws Exception
    {
        // The result is shared by the code macros of all the documents, so it doesn't depend on the context document.
        when(this.documentAccessBridge.getCurrentDocumentReference()).thenReturn(this.contextDocument);
        when(this.codeMacro.executeCodeMacro(any(), eq(CONTENT), any()))
            .thenReturn(Collections.singletonList(new WordBlock("highlighted")));
        BlockRenderer blockRenderer = mockBlockRenderer();

        getId(createContext(PAGE, 1, CONTENT), CONTENT, javaParameters());
        this.renderer.render(true, true);

        verify(blockRenderer).render(any(Block.class), any());
        verify(this.documentAccessBridge, never()).pushDocumentInContext(any(), any(DocumentReference.class));
    }

    @Test
    void renderSynchronouslyKeepsContextDocument() throws Exception
    {
        // The synchronous rendering already happens in the document containing the macro.
        CodeMacroParameters parameters = javaParameters();
        parameters.setTitle("[[Missing]]");
        when(this.baseReferenceResolver.resolve(any())).thenReturn(BASE);
        when(this.documentAccessBridge.getCurrentDocumentReference()).thenReturn(this.contextDocument);
        List<Block> highlighted = Collections.singletonList(new WordBlock("highlighted"));
        when(this.codeMacro.executeCodeMacro(any(), eq(CONTENT), any())).thenReturn(highlighted);

        getId(createContext(PAGE, 1, CONTENT), CONTENT, parameters);

        assertEquals(List.of(new MetaDataBlock(highlighted, MetaData.BASE, BASE)),
            this.renderer.render(false, true).getBlock().getChildren());
        verify(this.documentAccessBridge, never()).pushDocumentInContext(any(), any(DocumentReference.class));
    }

    private BlockRenderer mockBlockRenderer() throws Exception
    {
        BlockRenderer blockRenderer = mock(BlockRenderer.class);
        when(this.contextComponentManager.getInstance(BlockRenderer.class, Syntax.XHTML_1_0.toIdString()))
            .thenReturn(blockRenderer);
        return blockRenderer;
    }

    private CodeMacroParameters javaParameters()
    {
        CodeMacroParameters parameters = new CodeMacroParameters();
        parameters.setLanguage("java");
        return parameters;
    }

    private MacroTransformationContext createContext(String source, int position, String content)
    {
        // Put the code macro at the requested position of a document.
        MacroBlock macroBlock = new MacroBlock("code", Collections.emptyMap(), content, false);
        MetaDataBlock documentBlock = new MetaDataBlock(Collections.emptyList(), MetaData.SOURCE, source);
        for (int i = 0; i < position; i++) {
            documentBlock.addChild(new ParagraphBlock(Collections.singletonList(new WordBlock("word"))));
        }
        documentBlock.addChild(macroBlock);
        XDOM xdom = new XDOM(Collections.singletonList(documentBlock));

        MacroTransformationContext context =
            new MacroTransformationContext(new TransformationContext(xdom, Syntax.XWIKI_2_1));
        context.getTransformationContext().setTargetSyntax(Syntax.XHTML_1_0);
        context.setXDOM(xdom);
        context.setCurrentMacroBlock(macroBlock);
        context.setSyntax(Syntax.XWIKI_2_1);
        return context;
    }

    private List<String> getId(MacroTransformationContext context, String content, CodeMacroParameters parameters)
        throws Exception
    {
        this.renderer.initialize(this.codeMacro, parameters, content, context, true);
        return this.renderer.getId();
    }
}
