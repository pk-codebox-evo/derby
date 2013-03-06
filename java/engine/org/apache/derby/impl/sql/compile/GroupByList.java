/*

   Derby - Class org.apache.derby.impl.sql.compile.GroupByList

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package	org.apache.derby.impl.sql.compile;

import java.util.List;
import org.apache.derby.iapi.sql.compile.C_NodeTypes;
import org.apache.derby.iapi.services.sanity.SanityManager;
import org.apache.derby.iapi.error.StandardException;
import org.apache.derby.iapi.reference.SQLState;
import org.apache.derby.iapi.reference.Limits;


/**
 * A GroupByList represents the list of expressions in a GROUP BY clause in
 * a SELECT statement.
 *
 */

public class GroupByList extends OrderedColumnList
{
	int		numGroupingColsAdded = 0;
	boolean         rollup = false;

	/**
		Add a column to the list

		@param column	The column to add to the list
	 */
	public void addGroupByColumn(GroupByColumn column)
	{
		addElement(column);
	}

	/**
		Get a column from the list

		@param position	The column to get from the list
	 */
	public GroupByColumn getGroupByColumn(int position)
	{
		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(position >=0 && position < size(),
					"position (" + position +
					") expected to be between 0 and " + size());
		}
		return (GroupByColumn) elementAt(position);
	}


	public void setRollup()
	{
		rollup = true;
	}
	public boolean isRollup()
	{
		return rollup;
	}
                        

	/**
	 * Get the number of grouping columns that need to be added to the SELECT list.
	 *
	 * @return int	The number of grouping columns that need to be added to
	 *				the SELECT list.
	 */
	public int getNumNeedToAddGroupingCols()
	{
		return numGroupingColsAdded;
	}

	/**
	 *  Bind the group by list.  Verify:
	 *		o  Number of grouping columns matches number of non-aggregates in
	 *		   SELECT's RCL.
	 *		o  Names in the group by list are unique
	 *		o  Names of grouping columns match names of non-aggregate
	 *		   expressions in SELECT's RCL.
	 *
	 * @param select		The SelectNode
	 * @param aggregateVector	The aggregate vector being built as we find AggregateNodes
	 *
	 * @exception StandardException		Thrown on error
	 */
	void bindGroupByColumns(SelectNode select, List aggregateVector)
					throws StandardException
	{
		FromList		 fromList = select.getFromList();
		ResultColumnList selectRCL = select.getResultColumns();
		SubqueryList	 dummySubqueryList =
									(SubqueryList) getNodeFactory().getNode(
													C_NodeTypes.SUBQUERY_LIST,
													getContextManager());
		int				 numColsAddedHere = 0;
		int				 size = size();

		/* Only 32677 columns allowed in GROUP BY clause */
		if (size > Limits.DB2_MAX_ELEMENTS_IN_GROUP_BY)
		{
			throw StandardException.newException(SQLState.LANG_TOO_MANY_ELEMENTS);
		}

		/* Bind the grouping column */
		for (int index = 0; index < size; index++)
		{
			GroupByColumn groupByCol = (GroupByColumn) elementAt(index);
			groupByCol.bindExpression(fromList,
									  dummySubqueryList, aggregateVector);
		}

		
		int				rclSize = selectRCL.size();
		for (int index = 0; index < size; index++)
		{
			boolean				matchFound = false;
			GroupByColumn		groupingCol = (GroupByColumn) elementAt(index);

			/* Verify that this entry in the GROUP BY list matches a
			 * grouping column in the select list.
			 */
			for (int inner = 0; inner < rclSize; inner++)
			{
				ResultColumn selectListRC = (ResultColumn) selectRCL.elementAt(inner);
				if (!(selectListRC.getExpression() instanceof ColumnReference)) {
					continue;
				}
				
				ColumnReference selectListCR = (ColumnReference) selectListRC.getExpression();

				if (selectListCR.isEquivalent(groupingCol.getColumnExpression())) { 
					/* Column positions for grouping columns are 0-based */
					groupingCol.setColumnPosition(inner + 1);

					/* Mark the RC in the SELECT list as a grouping column */
					selectListRC.markAsGroupingColumn();
					matchFound = true;
					break;
				}
			}
			/* If no match found in the SELECT list, then add a matching
			 * ResultColumn/ColumnReference pair to the SelectNode's RCL.
			 * However, don't add additional result columns if the query
			 * specified DISTINCT, because distinct processing considers
			 * the entire RCL and including extra columns could change the
			 * results: e.g. select distinct a,b from t group by a,b,c
			 * should not consider column c in distinct processing (DERBY-3613)
			 */
			if (! matchFound && !select.hasDistinct() &&
			    groupingCol.getColumnExpression() instanceof ColumnReference) 
			{
			    	// only add matching columns for column references not 
			    	// expressions yet. See DERBY-883 for details. 
				ResultColumn newRC;

				/* Get a new ResultColumn */
				newRC = (ResultColumn) getNodeFactory().getNode(
								C_NodeTypes.RESULT_COLUMN,
								groupingCol.getColumnName(),
								groupingCol.getColumnExpression().getClone(),
								getContextManager());
				newRC.setVirtualColumnId(selectRCL.size() + 1);
				newRC.markGenerated();
				newRC.markAsGroupingColumn();

				/* Add the new RC/CR to the RCL */
				selectRCL.addElement(newRC);

				/* Set the columnPosition in the GroupByColumn, now that it
				* has a matching entry in the SELECT list.
				*/
				groupingCol.setColumnPosition(selectRCL.size());
				
				// a new hidden or generated column is added to this RCL
				// i.e. that the size() of the RCL != visibleSize(). 
				// Error checking done later should be aware of this 
				// special case.
				selectRCL.setCountMismatchAllowed(true);

				/*
				** Track the number of columns that we have added
				** in this routine.  We track this separately
				** than the total number of columns added by this
				** object (numGroupingColsAdded) because we
				** might be bound (though not gagged) more than
				** once (in which case numGroupingColsAdded will
				** already be set).
				*/
				numColsAddedHere++;
			}
			if (groupingCol.getColumnExpression() instanceof JavaToSQLValueNode) 
			{
				// disallow any expression which involves native java computation. 
				// Not possible to consider java expressions for equivalence.
				throw StandardException.newException(					
						SQLState.LANG_INVALID_GROUPED_SELECT_LIST);
			}
		}

		/* Verify that no subqueries got added to the dummy list */
		if (SanityManager.DEBUG)
		{
			SanityManager.ASSERT(dummySubqueryList.size() == 0,
				"dummySubqueryList.size() is expected to be 0");
		}

		numGroupingColsAdded+= numColsAddedHere;
	}

	

	/**
	 * Find the matching grouping column if any for the given expression
	 * 
	 * @param node an expression for which we are trying to find a match
	 * in the group by list.
	 * 
	 * @return the matching GroupByColumn if one exists, null otherwise.
	 * 
	 * @throws StandardException
	 */
	public GroupByColumn findGroupingColumn(ValueNode node)
	        throws StandardException
	{
		int sz = size();
		for (int i = 0; i < sz; i++) 
		{
			GroupByColumn gbc = (GroupByColumn)elementAt(i);
			if (gbc.getColumnExpression().isEquivalent(node))
			{
				return gbc;
			}
		}
		return null;
	}
	
	/**
	 * Remap all ColumnReferences in this tree to be clones of the
	 * underlying expression.
	 *
	 * @exception StandardException			Thrown on error
	 */
	public void remapColumnReferencesToExpressions() throws StandardException
	{
		GroupByColumn	gbc;
		int				size = size();

		/* This method is called when flattening a FromTable.  We should
		 * not be flattening a FromTable if the underlying expression that
		 * will get returned out, after chopping out the redundant ResultColumns,
		 * is not a ColumnReference.  (See ASSERT below.)
		 */
		for (int index = 0; index < size; index++)
		{
			ValueNode	retVN;
			gbc = (GroupByColumn) elementAt(index);

			retVN = gbc.getColumnExpression().remapColumnReferencesToExpressions();

			if (SanityManager.DEBUG)
			{
				SanityManager.ASSERT(retVN instanceof ColumnReference,
					"retVN expected to be instanceof ColumnReference, not " +
					retVN.getClass().getName());
			}

			gbc.setColumnExpression(retVN);
		}
	}


	/**
	 * Convert this object to a String.  See comments in QueryTreeNode.java
	 * for how this should be done for tree printing.
	 *
	 * @return	This object as a String
	 */
	public String toString()
	{
		if (SanityManager.DEBUG) {
			return "numGroupingColsAdded: " + numGroupingColsAdded + "\n" +
				super.toString();
		} else {
			return "";
		}
	}


	public void preprocess(
			int numTables, FromList fromList, SubqueryList whereSubquerys, 
			PredicateList wherePredicates) throws StandardException 
	{
		for (int index = 0; index < size(); index++)
		{
			GroupByColumn	groupingCol = (GroupByColumn) elementAt(index);
			groupingCol.setColumnExpression(
					groupingCol.getColumnExpression().preprocess(
							numTables, fromList, whereSubquerys, wherePredicates));
		}		
	}
}
