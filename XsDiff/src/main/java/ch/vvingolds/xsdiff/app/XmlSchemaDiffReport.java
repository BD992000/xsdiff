/*
  This file is licensed to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package ch.vvingolds.xsdiff.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

import org.outerj.daisy.diff.DaisyDiff;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.ContentHandler;
import org.xmlunit.diff.Comparison;
import org.xmlunit.diff.Comparison.Detail;
import org.xmlunit.diff.ComparisonType;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.Difference;

import com.google.common.collect.Maps;

/** XML Schema (XSD) comparison/report generator */
public class XmlSchemaDiffReport {

    private final XmlDomUtils xmlDomUtils = new XmlDomUtils();
    private final NodeToString printNode = new NodeToString();
    private final HtmlContentOutput output;

    private final Map<String, NodeChangesHolder> nodeChanges = Maps.newLinkedHashMap();

    public XmlSchemaDiffReport( final HtmlContentOutput output ) {
        this.output = output;
    }

    private static boolean isAdded( final Comparison comparison ) {
        return comparison.getControlDetails().getTarget() == null;
    }

    private static boolean isDeleted( final Comparison comparison ) {
        return comparison.getTestDetails().getTarget() == null;
    }

    public void runDiff( final Document controlDoc, final Document testDoc ) {

        final Diff xmlDiff = new XmlSchemaDiffBuilder().compare( controlDoc, testDoc );

        output.write( "TYPE ; XPATH ; OLD VALUE" );
        for( final Difference diff : xmlDiff.getDifferences() ) {
            final Comparison comparison = diff.getComparison();
            if( isAdded( comparison ) ) {
                printAddedNode( testDoc, comparison );
            }
            else if( isDeleted( comparison ) ) {
                printDeletedNode( controlDoc, comparison );
            }
            else {
                printModifiedNode( testDoc, controlDoc, comparison );
            }
        }

        output.write( "++ ADDS ; REMOVES ++" );
        printAddsAndRemoves();
    }

    private void printAddsAndRemoves() {
        for( final Map.Entry<String, NodeChangesHolder> entry : nodeChanges.entrySet() ) {
            output.markChanges( entry.getKey(), entry.getValue() );
        }
    }

    private void printAddedNode( final Document testDoc, final Comparison comparison ) {
        final Comparison.Detail details = comparison.getTestDetails();
        final Node parentNode = xmlDomUtils.findNode( testDoc, details.getParentXPath() );
        output.write( "ADDED <!-- xpath: " + details.getXPath() + " (parent node: "+printNode.printNodeSignature( parentNode )+" - "+details.getParentXPath()+" ) -->");

        final String nodeText = printNode.nodeToString( xmlDomUtils.findNode( testDoc, details.getXPath() ) );
        output.addAddedPart( nodeText );
        output.newline();

        if( ! markNodeAdded( details.getParentXPath(), nodeText, testDoc ) ) {
            output.write( "! holder for "+  details.getParentXPath() + " did not exist(?)");
            final String parentText = printNode.printNodeWithParentInfo( parentNode, details.getParentXPath() );
            output.writeLong( parentText );
            output.markPartAdded( parentText, Collections.singletonList( nodeText ) );
        }
    }

    private void printDeletedNode( final Document controlDoc, final Comparison comparison ) {
        final Comparison.Detail details = comparison.getControlDetails();
        final Node parentNode = xmlDomUtils.findNode( controlDoc, details.getParentXPath() );
        output.write( "DELETED <!-- xpath: " + details.getXPath() + " (parent node: "+printNode.printNodeSignature( parentNode )+" - "+details.getParentXPath()+" ) -->" );

        final String nodeText = printNode.nodeToString( xmlDomUtils.findNode( controlDoc, details.getXPath() ) );
        output.addRemovedPart( nodeText );
        output.newline();

        if( ! markNodeRemoved( details.getParentXPath(), nodeText, controlDoc ) ) {
            output.write( "! holder for "+  details.getParentXPath() + " did not exist(?)");
            final String parentText = printNode.printNodeWithParentInfo( parentNode, details.getParentXPath() );
            output.writeLong( parentText );
            output.markPartRemoved( parentText, Collections.singletonList( nodeText ) );
        }
    }

    /** @return false, if change could not be posted (parent holder did not exist). caller should print change explicitly. */
    private boolean markNodeAdded( final String parentXpath, final String nodeText, final Document parentDoc ) {
        final NodeChangesHolder changeHolder = getOrAddHolder( parentXpath, parentDoc, "added" );
        if( changeHolder == null ) {
            return false;
        }

        changeHolder.addedNode( nodeText );
        return true;
    }

    /** create holder on the fly for certain add/remove operations
     * @param opType make sure we can have a holder for each add/remove because parent text is different
     */
    private NodeChangesHolder getOrAddHolder( final String parentXpath, final Document parentDoc, final String opType ) {
        final NodeChangesHolder changeHolder = nodeChanges.get( parentXpath );
        if( changeHolder != null ) {
            return changeHolder;
        }

        return addHolder( parentXpath, parentDoc, opType );
    }

    /** check of change holder should be created, if one does not exist. verifies that the node is not located too shallow (i.e., we don't want to track stuff added under doc root) */
    public NodeChangesHolder addHolder( final String parentXpath, final Document parentDoc, final String opType ) {
        final long xpathDepth = XmlDomUtils.xpathDepth( parentXpath );
        final boolean tooShallow = xpathDepth < 2;
        if( tooShallow ) {
            return null;
        }

        // should mark anyway
        return addChangeHolder( opType + "-" + parentXpath, printNode.nodeToString( xmlDomUtils.findNode( parentDoc, parentXpath ) ) );
    }

    /** @return false, if change could not be posted (parent holder did not exist). caller should print change explicitly. */
    private boolean markNodeRemoved( final String parentXpath, final String nodeText, final Document parentDoc ) {
        final NodeChangesHolder changeHolder = getOrAddHolder( parentXpath, parentDoc, "removed" );
        if( changeHolder == null ) {
            return false;
        }

        changeHolder.removedNode( nodeText );
        return true;
    }

    private void printModifiedNode( final Document testDoc, final Document controlDoc, final Comparison comparison ) {

        final Comparison.Detail details = comparison.getControlDetails();
        if( XmlDomUtils.xpathDepth( details.getXPath() ) == 1 ) {
            output.write( "MODIFIED ; " + details.getXPath() + "." );
        }
        else {
            if( comparison.getType() == ComparisonType.CHILD_NODELIST_SEQUENCE ) {
                output.write( ". node order different: " + comparison.getTestDetails().getXPath() );
            }
            else if( comparison.getType() == ComparisonType.CHILD_NODELIST_LENGTH ) {
                final long xpathDepth = XmlDomUtils.xpathDepth( details.getXPath() );
                final boolean shouldTakeParent = xpathDepth > 2;

                final int sizeControl = (int)comparison.getControlDetails().getValue();
                final int sizeTest = (int)comparison.getTestDetails().getValue();
                if( sizeTest > sizeControl ) {
                    // nodes added
                    output.write( String.format( ". %s node(s) added: %s <!-- %s -->", sizeTest - sizeControl, printNode.printNodeSignature( comparison.getTestDetails().getTarget() ), comparison.getTestDetails().getXPath() ) );
                    addChangeHolder( comparison.getTestDetails().getXPath(), holderNodeText( testDoc, comparison.getTestDetails() ) );
                }
                else {
                    // nodes removed
                    output.write( String.format( ". %s node(s) removed: %s <!-- %s -->", sizeControl - sizeTest, printNode.printNodeSignature( comparison.getTestDetails().getTarget() ), comparison.getTestDetails().getXPath() ) );
                    addChangeHolder( comparison.getControlDetails().getXPath(), holderNodeText( controlDoc, comparison.getControlDetails() ) );
                }

                if( shouldTakeParent ) {
                    final String oldText = printNode.nodeToString( xmlDomUtils.findNode( controlDoc, comparison.getControlDetails().getParentXPath() ) );
                    final String newText = printNode.nodeToString( xmlDomUtils.findNode( testDoc, comparison.getTestDetails().getParentXPath() ) );
                    output.write( "~" );
                    daisyDiff( oldText, newText, output.getHandler() );
                    output.write( "~" );
                }

            }
            else if( comparison.getType() == ComparisonType.ATTR_NAME_LOOKUP ) {
                printNewAttr( comparison.getTestDetails() );
            }
            else {
                printNodeDiff( testDoc, comparison );
            }
        }
    }

    private NodeChangesHolder addChangeHolder( final String xpathExpr, final String nodeText ) {
        if( nodeChanges.containsKey(  xpathExpr ) ) {
            return nodeChanges.get( xpathExpr ); // should not happen
        }

        final NodeChangesHolder changesHolder = new NodeChangesHolder( nodeText );
        nodeChanges.put( xpathExpr, changesHolder );
        return changesHolder;
    }

    /** this one is clever enough to expand node text up to parent node scope, to provide interesting context when changes are printed */
    private String holderNodeText( final Document doc, final Detail details ) {
        final long xpathDepth = XmlDomUtils.xpathDepth( details.getXPath() );
        final boolean shouldTakeParent = xpathDepth > 2;
        final String xpathExpr = shouldTakeParent ? details.getParentXPath() : details.getXPath();
        final String nodeText = printNode.nodeToString( xmlDomUtils.findNode( doc, xpathExpr ) );
        return nodeText;
    }

    /** only info about new attr value */
    private void printNewAttr( final Detail detail ) {
        output.write( "MODIFIED ; new attribute [" + printNode.attrToString( detail.getTarget(), (QName)detail.getValue() ) + "] <!-- xpath: " + detail.getXPath() + " -->" );
    }

    private void printNodeDiff( final Document testDoc, final Comparison comparison ) {
        output.write( "MODIFIED ; " + comparison.toString() + "\n" );
        final String oldText = printNode.nodeToString( comparison.getControlDetails().getTarget() );
        final String newText = printNode.nodeToString( comparison.getTestDetails().getTarget() );
        output.write( "- " + oldText );
        output.write( "+ " + newText );

        output.write( "~" );
        daisyDiff( oldText, newText, output.getHandler() );
        output.write( "~" );

        final Node parentNode = xmlDomUtils.findNode( testDoc, comparison.getTestDetails().getParentXPath() );
        output.writeLong( printNode.printNodeWithParentInfo( parentNode, comparison.getTestDetails().getParentXPath() ) );
    }

    protected String daisyDiff( final String oldText, final String newText ) {

        try {
            final SAXTransformerFactory tf = XmlDomUtils.saxTransformerFactory();

            final TransformerHandler resultHandler = XmlDomUtils.newFragmentTransformerHandler( tf );

            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            resultHandler.setResult(new StreamResult( bytes ));

            daisyDiff( oldText, newText, resultHandler );

            return new String( bytes.toByteArray(), StandardCharsets.UTF_8 );
        }
        catch( final Exception e ) {
            return "(failed to daisydiff: "+e+")";
        }
    }

    public void daisyDiff( final String oldText, final String newText, final ContentHandler resultHandler ) {
        try {
            DaisyDiff.diffTag( oldText, newText, resultHandler );
        }
        catch( final Exception e ) {
            output.write( "(failed to daisydiff: "+e+")" );
        }
    }


}
