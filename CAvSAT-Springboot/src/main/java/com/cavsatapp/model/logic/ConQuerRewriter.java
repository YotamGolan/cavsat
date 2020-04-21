/*******************************************************************************
 * Copyright 2020 Regents of the University of California. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be found in the LICENSE.txt file at the root of the project.
 ******************************************************************************/

package com.cavsatapp.model.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.cavsatapp.model.bean.SQLQuery;
import com.cavsatapp.model.bean.Schema;

public class ConQuerRewriter {
	public String rewriteForestSQL(SQLQuery sqlQuery, Schema schema, short[][] joinGraph) {
		String candidates, countViolSubQuery, countProjSubQuery, rewriting;
		StringBuilder sb;
		List<String> s1toSl = null, k1toKn = new ArrayList<String>();
		s1toSl = sqlQuery.getSelect();
		for (String relationName : sqlQuery.getFrom())
			k1toKn.addAll(schema.getRelationByName(relationName).getKeyAttributesList());

		// Candidates
		sb = new StringBuilder("SELECT ");
		sb.append(k1toKn.stream().map(k -> k + " AS c" + k).collect(Collectors.joining(", ")));
		sb.append(" FROM " + sqlQuery.getFrom().stream().collect(Collectors.joining(", ")));
		if (!sqlQuery.getWhereConditions().isEmpty())
			sb.append(" WHERE " + sqlQuery.getWhereConditions().stream().collect(Collectors.joining(" AND ")));
		candidates = sb.toString();

		// countViolSubQuery
		sb = new StringBuilder("SELECT ");
		sb.append(k1toKn.stream().collect(Collectors.joining(", ")) + ", "
				+ s1toSl.stream().collect(Collectors.joining(", ")));
		sb.append(", RANK() OVER (PARTITION BY " + k1toKn.stream().collect(Collectors.joining(", ")) + " ORDER BY "
				+ s1toSl.stream().collect(Collectors.joining(", ")) + ") AS rankProjection");
		sb.append(", SUM(CASE WHEN (");
		if (!sqlQuery.getWhereConditions().isEmpty())
			sb.append(sqlQuery.getWhereConditions().stream().collect(Collectors.joining(" AND ")));
		else
			sb.append("1=1");
		sb.append(") THEN 0 ELSE 1 END)");
		sb.append(" OVER (PARTITION BY " + k1toKn.stream().collect(Collectors.joining(", ")) + ") AS countViol");
		sb.append(" FROM ").append(getJoinsExpression(joinGraph, getEquiJoinConditions(sqlQuery), sqlQuery.getFrom()));
		sb.append(" WHERE EXISTS (SELECT * FROM Candidates WHERE "
				+ k1toKn.stream().map(k -> k + " = c" + k).collect(Collectors.joining(" AND ")) + ")");
		countViolSubQuery = sb.toString();

		// countProjSubQuery
		sb = new StringBuilder("SELECT ");
		sb.append(k1toKn.stream().collect(Collectors.joining(", ")) + ", "
				+ s1toSl.stream().map(si -> si.substring(si.lastIndexOf(".") + 1)).collect(Collectors.joining(", ")));
		sb.append(", MAX(rankProjection) OVER (PARTITION BY " + k1toKn.stream().collect(Collectors.joining(", "))
				+ ") AS countProjection");
		sb.append(", countViol");
		sb.append(" FROM countViolSubQuery");
		countProjSubQuery = sb.toString();

		// rewriting
		sb = new StringBuilder("SELECT DISTINCT ");
		sb.append(s1toSl.stream().map(si -> si.substring(si.lastIndexOf(".") + 1)).collect(Collectors.joining(", ")));
		sb.append(" FROM countProjSubQuery");
		sb.append(" WHERE countProjection = 1 AND countViol = 0");
		rewriting = sb.toString();
		return "WITH Candidates AS (" + candidates + "), countViolSubQuery AS (" + countViolSubQuery
				+ "), countProjSubQuery AS (" + countProjSubQuery + ") " + rewriting;
	}

	private String getJoinsExpression(short[][] joinGraph, List<String> joinConditions, List<String> relationNames) {
		List<Integer> roots = new ArrayList<Integer>();
		boolean isRoot;
		for (int i = 0; i < joinGraph.length; i++) {
			isRoot = true; // Assume i is a root of a tree in the join graph
			for (int j = 0; j < joinGraph.length; j++)
				if (joinGraph[j][i] > 0) {
					isRoot = false; // found that i is not a root
					break;
				}
			if (isRoot) // i is really a root
				roots.add(i);
		}
		StringBuilder rjoins = new StringBuilder(relationNames.get(roots.get(0)));
		List<String> iJoins = new ArrayList<String>();
		for (int i = 1; i < roots.size(); i++) {
			String ri = relationNames.get(roots.get(i));
			String riMinus1 = relationNames.get(roots.get(i - 1));
			iJoins.clear();
			for (String condition : joinConditions) {
				String[] parts = condition.split(" = ");
				if ((parts[0].startsWith(ri) && parts[1].startsWith(riMinus1))
						|| parts[1].startsWith(ri) && parts[0].startsWith(riMinus1)) {
					iJoins.add(condition);
				}
			}
			rjoins.append(" JOIN " + ri + " ON " + iJoins.stream().collect(Collectors.joining(" AND ")));
		}
		StringBuilder tjoins = new StringBuilder("");
		for (int t : roots) {
			tjoins.append(getTreeJoinsExpression(t, joinGraph, joinConditions, relationNames));
		}
		String rjoinsStr = rjoins.toString().trim();
		String tjoinsStr = tjoins.toString().trim();
		if (rjoinsStr.isEmpty())
			return tjoinsStr;
		else if (tjoinsStr.isEmpty())
			return rjoinsStr;
		return rjoins.append(" AND " + tjoins.toString()).toString();
	}

	private String getTreeJoinsExpression(int t, short[][] joinGraph, List<String> joinConditions,
			List<String> relationNames) {
		StringBuilder loJoins = new StringBuilder("");
		List<String> children = new ArrayList<String>();
		List<String> iJoins = new ArrayList<String>();
		for (short i = 0; i < joinGraph.length; i++) {
			if (joinGraph[t][i] > 0)
				children.add(relationNames.get(i));
		}
		if (children.isEmpty())
			return "";
		for (String child : children) {
			iJoins.clear();
			for (String condition : joinConditions) {
				String[] parts = condition.split(" = ");
				if ((parts[0].startsWith(child) && parts[1].startsWith(relationNames.get(t)))
						|| parts[1].startsWith(child) && parts[0].startsWith(relationNames.get(t))) {
					iJoins.add(condition);
				}
			}
			loJoins.append("LEFT OUTER JOIN " + child + " ON " + iJoins.stream().collect(Collectors.joining(" AND ")));
		}
		for (String child : children) {
			loJoins.append(
					getTreeJoinsExpression(relationNames.indexOf(child), joinGraph, joinConditions, relationNames));
		}
		return loJoins.toString();
	}

	private List<String> getEquiJoinConditions(SQLQuery sqlQuery) {
		List<String> joinConditions = new ArrayList<String>();
		for (String condition : sqlQuery.getWhereConditions())
			if (condition.contains(" = "))
				joinConditions.add(condition);
		return joinConditions;
	}
}
