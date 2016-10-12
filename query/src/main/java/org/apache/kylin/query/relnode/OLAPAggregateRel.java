/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.query.relnode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.calcite.adapter.enumerable.EnumerableAggregate;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.AggregateFunction;
import org.apache.calcite.schema.FunctionParameter;
import org.apache.calcite.schema.impl.AggregateFunctionImpl;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.validate.SqlUserDefinedAggFunction;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Util;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.measure.bitmap.BitmapMeasureType;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.ParameterDesc;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.query.schema.OLAPTable;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 */
public class OLAPAggregateRel extends Aggregate implements OLAPRel {

    private final static Map<String, String> AGGR_FUNC_MAP = new HashMap<String, String>();

    static {
        AGGR_FUNC_MAP.put("SUM", "SUM");
        AGGR_FUNC_MAP.put("$SUM0", "SUM");
        AGGR_FUNC_MAP.put("COUNT", "COUNT");
        AGGR_FUNC_MAP.put("COUNT_DISTINCT", "COUNT_DISTINCT");
        AGGR_FUNC_MAP.put(BitmapMeasureType.FUNC_INTERSECT_COUNT_DISTINCT, "COUNT_DISTINCT");
        AGGR_FUNC_MAP.put("MAX", "MAX");
        AGGR_FUNC_MAP.put("MIN", "MIN");

        for (String customAggrFunc : KylinConfig.getInstanceFromEnv().getCubeCustomMeasureTypes().keySet()) {
            AGGR_FUNC_MAP.put(customAggrFunc.trim().toUpperCase(), customAggrFunc.trim().toUpperCase());
        }
    }

    private static String getFuncName(AggregateCall aggCall) {
        String aggName = aggCall.getAggregation().getName();
        if (aggCall.isDistinct()) {
            aggName = aggName + "_DISTINCT";
        }
        String funcName = AGGR_FUNC_MAP.get(aggName);
        if (funcName == null) {
            throw new IllegalStateException("Don't suppoprt aggregation " + aggName);
        }
        return funcName;
    }

    private OLAPContext context;
    private ColumnRowType columnRowType;
    private boolean afterAggregate;
    private List<AggregateCall> rewriteAggCalls;
    private List<TblColRef> groups;
    private List<FunctionDesc> aggregations;

    public OLAPAggregateRel(RelOptCluster cluster, RelTraitSet traits, RelNode child, boolean indicator, ImmutableBitSet groupSet, List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) throws InvalidRelException {
        super(cluster, traits, child, indicator, groupSet, groupSets, aggCalls);
        Preconditions.checkArgument(getConvention() == OLAPRel.CONVENTION);
        this.afterAggregate = false;
        this.rewriteAggCalls = aggCalls;
        this.rowType = getRowType();
    }

    @Override
    public Aggregate copy(RelTraitSet traitSet, RelNode input, boolean indicator, ImmutableBitSet groupSet, List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
        try {
            return new OLAPAggregateRel(getCluster(), traitSet, input, indicator, groupSet, groupSets, aggCalls);
        } catch (InvalidRelException e) {
            throw new IllegalStateException("Can't create OLAPAggregateRel!", e);
        }
    }

    /**
     * Since the grouping aggregate will be expanded by {@link org.apache.kylin.query.optrule.AggregateMultipleExpandRule},
     * made the cost of grouping aggregate more expensive to use the expanded aggregates
     */
    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
        RelOptCost cost;
        if (getGroupType() == Group.SIMPLE) {
            cost = super.computeSelfCost(planner, mq).multiplyBy(.05);
        } else {
            cost = super.computeSelfCost(planner, mq).multiplyBy(.05).plus(planner.getCost(getInput(), mq)).multiplyBy(groupSets.size() * 1.5);
        }
        return cost;
    }

    @Override
    public void implementOLAP(OLAPImplementor implementor) {

        implementor.visitChild(getInput(), this);

        this.context = implementor.getContext();
        this.columnRowType = buildColumnRowType();
        this.afterAggregate = this.context.afterAggregate;

        // only translate the innermost aggregation
        if (!this.afterAggregate) {
            translateGroupBy();
            this.context.aggregations.addAll(this.aggregations);
            this.context.afterAggregate = true;
        } else {
            for (AggregateCall aggCall : aggCalls) {
                // check if supported by kylin
                if (aggCall.isDistinct()) {
                    throw new IllegalStateException("Distinct count is only allowed in innermost sub-query.");
                }
            }
        }
    }

    private ColumnRowType buildColumnRowType() {
        buildGroups();
        buildAggregations();

        ColumnRowType inputColumnRowType = ((OLAPRel) getInput()).getColumnRowType();
        List<TblColRef> columns = new ArrayList<TblColRef>(this.rowType.getFieldCount());
        columns.addAll(this.groups);

        // Add group column indicators
        if (indicator) {
            final Set<String> containedNames = Sets.newHashSet();
            for (TblColRef groupCol : groups) {
                String base = "i$" + groupCol.getName();
                String name = base;
                int i = 0;
                while (containedNames.contains(name)) {
                    name = base + "_" + i++;
                }
                containedNames.add(name);
                TblColRef indicatorCol = TblColRef.newInnerColumn(name, TblColRef.InnerDataTypeEnum.LITERAL);
                columns.add(indicatorCol);
            }
        }

        for (int i = 0; i < this.aggregations.size(); i++) {
            FunctionDesc aggFunc = this.aggregations.get(i);
            TblColRef aggCol = null;
            if (aggFunc.needRewriteField()) {
                aggCol = buildRewriteColumn(aggFunc);
            } else {
                AggregateCall aggCall = this.rewriteAggCalls.get(i);
                if (!aggCall.getArgList().isEmpty()) {
                    int index = aggCall.getArgList().get(0);
                    aggCol = inputColumnRowType.getColumnByIndex(index);
                }
            }
            columns.add(aggCol);
        }
        return new ColumnRowType(columns);
    }

    private TblColRef buildRewriteColumn(FunctionDesc aggFunc) {
        TblColRef colRef;
        if (aggFunc.needRewriteField()) {
            ColumnDesc column = new ColumnDesc();
            column.setName(aggFunc.getRewriteFieldName());
            TableDesc table = this.context.firstTableScan.getOlapTable().getSourceTable();
            column.setTable(table);
            colRef = column.getRef();
        } else {
            throw new IllegalStateException("buildRewriteColumn on a aggrFunc that does not need rewrite " + aggFunc);
        }
        return colRef;
    }

    private void buildGroups() {
        ColumnRowType inputColumnRowType = ((OLAPRel) getInput()).getColumnRowType();
        this.groups = new ArrayList<TblColRef>();
        for (int i = getGroupSet().nextSetBit(0); i >= 0; i = getGroupSet().nextSetBit(i + 1)) {
            Set<TblColRef> columns = inputColumnRowType.getSourceColumnsByIndex(i);
            this.groups.addAll(columns);
        }
        // Some UDAF may group data by itself, add group key into groups, prevents aggregate at cube storage server side
        for (AggregateCall aggCall : this.rewriteAggCalls) {
            String aggregateName = aggCall.getAggregation().getName();
            if (aggregateName.equalsIgnoreCase(BitmapMeasureType.FUNC_INTERSECT_COUNT_DISTINCT)) {
                int index = aggCall.getArgList().get(1);
                TblColRef column = inputColumnRowType.getColumnByIndex(index);
                groups.add(column);
            }
        }
    }

    private void buildAggregations() {
        ColumnRowType inputColumnRowType = ((OLAPRel) getInput()).getColumnRowType();
        this.aggregations = new ArrayList<FunctionDesc>();
        for (AggregateCall aggCall : this.rewriteAggCalls) {
            ParameterDesc parameter = null;
            if (!aggCall.getArgList().isEmpty()) {
                // TODO: Currently only get the column of first param
                int index = aggCall.getArgList().get(0);
                TblColRef column = inputColumnRowType.getColumnByIndex(index);
                if (!column.isInnerColumn()) {
                    parameter = new ParameterDesc();
                    parameter.setValue(column.getName());
                    parameter.setType(FunctionDesc.PARAMETER_TYPE_COLUMN);
                    parameter.setColRefs(Arrays.asList(column));
                }
            }
            FunctionDesc aggFunc = new FunctionDesc();
            String funcName = getFuncName(aggCall);
            aggFunc.setExpression(funcName);
            aggFunc.setParameter(parameter);
            this.aggregations.add(aggFunc);
        }
    }

    private void translateGroupBy() {
        context.groupByColumns.addAll(this.groups);
    }

    @Override
    public void implementRewrite(RewriteImplementor implementor) {
        // only rewrite the innermost aggregation
        if (!this.afterAggregate) {
            translateAggregation();
            buildRewriteFieldsAndMetricsColumns();
        }

        implementor.visitChild(this, getInput());

        // only rewrite the innermost aggregation
        if (!this.afterAggregate) {
            // rewrite the aggCalls
            this.rewriteAggCalls = new ArrayList<AggregateCall>(aggCalls.size());
            for (int i = 0; i < this.aggCalls.size(); i++) {
                AggregateCall aggCall = this.aggCalls.get(i);
                FunctionDesc cubeFunc = this.context.aggregations.get(i);
                if (cubeFunc.needRewrite()) {
                    aggCall = rewriteAggregateCall(aggCall, cubeFunc);
                }
                this.rewriteAggCalls.add(aggCall);
            }
        }

        // rebuild rowType & columnRowType
        this.rowType = this.deriveRowType();
        this.columnRowType = this.buildColumnRowType();

    }

    private void translateAggregation() {
        // now the realization is known, replace aggregations with what's defined on MeasureDesc
        List<MeasureDesc> measures = this.context.realization.getMeasures();
        List<FunctionDesc> newAggrs = Lists.newArrayList();
        for (FunctionDesc aggFunc : this.aggregations) {
            newAggrs.add(findInMeasures(aggFunc, measures));
        }
        this.aggregations.clear();
        this.aggregations.addAll(newAggrs);
        this.context.aggregations.clear();
        this.context.aggregations.addAll(newAggrs);
    }

    private FunctionDesc findInMeasures(FunctionDesc aggFunc, List<MeasureDesc> measures) {
        for (MeasureDesc m : measures) {
            if (aggFunc.equals(m.getFunction()))
                return m.getFunction();
        }
        return aggFunc;
    }

    private void buildRewriteFieldsAndMetricsColumns() {
        fillbackOptimizedColumn();

        ColumnRowType inputColumnRowType = ((OLAPRel) getInput()).getColumnRowType();
        RelDataTypeFactory typeFactory = getCluster().getTypeFactory();
        for (int i = 0; i < this.aggregations.size(); i++) {
            FunctionDesc aggFunc = this.aggregations.get(i);

            if (aggFunc.isDimensionAsMetric()) {
                this.context.groupByColumns.addAll(aggFunc.getParameter().getColRefs());
                continue; // skip rewrite, let calcite handle
            }

            if (aggFunc.needRewriteField()) {
                String rewriteFieldName = aggFunc.getRewriteFieldName();
                RelDataType rewriteFieldType = OLAPTable.createSqlType(typeFactory, aggFunc.getRewriteFieldType(), true);
                this.context.rewriteFields.put(rewriteFieldName, rewriteFieldType);

                TblColRef column = buildRewriteColumn(aggFunc);
                this.context.metricsColumns.add(column);
            }

            AggregateCall aggCall = this.rewriteAggCalls.get(i);
            if (!aggCall.getArgList().isEmpty()) {
                int index = aggCall.getArgList().get(0);
                TblColRef column = inputColumnRowType.getColumnByIndex(index);
                if (!column.isInnerColumn()) {
                    this.context.metricsColumns.add(column);
                }
            }
        }
    }

    private void fillbackOptimizedColumn() {
        // some aggcall will be optimized out in sub-query (e.g. tableau generated sql), we need to fill them back
        RelDataType inputAggRow = getInput().getRowType();
        RelDataType outputAggRow = getRowType();
        if (inputAggRow.getFieldCount() != outputAggRow.getFieldCount()) {
            for (RelDataTypeField inputField : inputAggRow.getFieldList()) {
                String inputFieldName = inputField.getName();
                // constant columns(starts with $) should not be added to context.
                if (!inputFieldName.startsWith("$") && outputAggRow.getField(inputFieldName, true, false) == null) {
                    TblColRef column = this.columnRowType.getColumnByIndex(inputField.getIndex());
                    this.context.metricsColumns.add(column);
                }
            }
        }
    }

    private boolean noPrecaculatedFieldsAvailable() {
        return !this.context.hasPrecalculatedFields() || !RewriteImplementor.needRewrite(this.context);
    }

    private AggregateCall rewriteAggregateCall(AggregateCall aggCall, FunctionDesc func) {

        //if it's not a cube, then the "needRewriteField func" should not resort to any rewrite fields, 
        // which do not exist at all
        if (noPrecaculatedFieldsAvailable() && func.needRewriteField()) {
            logger.info(func + "skip rewriteAggregateCall because no pre-aggregated field available");
            return aggCall;
        }

        // rebuild parameters
        List<Integer> newArgList = Lists.newArrayList(aggCall.getArgList());
        if (func.needRewriteField()) {
            RelDataTypeField field = getInput().getRowType().getField(func.getRewriteFieldName(), true, false);
            if (newArgList.isEmpty()) {
                newArgList.add(field.getIndex());
            } else {
                // only the first column got overwritten
                newArgList.set(0, field.getIndex());
            }
        }

        // rebuild function
        String callName = aggCall.getAggregation().getName();
        RelDataType fieldType = aggCall.getType();
        SqlAggFunction newAgg = aggCall.getAggregation();
        if (func.isCount()) {
            newAgg = SqlStdOperatorTable.SUM0;
        } else if (func.getMeasureType().getRewriteCalciteAggrFunctionClass() != null) {
            newAgg = createCustomAggFunction(callName, fieldType, func.getMeasureType().getRewriteCalciteAggrFunctionClass(callName));
        }

        // rebuild aggregate call
        AggregateCall newAggCall = new AggregateCall(newAgg, false, newArgList, fieldType, callName);
        return newAggCall;
    }

    private SqlAggFunction createCustomAggFunction(String funcName, RelDataType returnType, Class<?> customAggFuncClz) {
        RelDataTypeFactory typeFactory = getCluster().getTypeFactory();
        SqlIdentifier sqlIdentifier = new SqlIdentifier(funcName, new SqlParserPos(1, 1));
        AggregateFunction aggFunction = AggregateFunctionImpl.create(customAggFuncClz);
        List<RelDataType> argTypes = new ArrayList<RelDataType>();
        List<SqlTypeFamily> typeFamilies = new ArrayList<SqlTypeFamily>();
        for (FunctionParameter o : aggFunction.getParameters()) {
            final RelDataType type = o.getType(typeFactory);
            argTypes.add(type);
            typeFamilies.add(Util.first(type.getSqlTypeName().getFamily(), SqlTypeFamily.ANY));
        }
        return new SqlUserDefinedAggFunction(sqlIdentifier, ReturnTypes.explicit(returnType), InferTypes.explicit(argTypes), OperandTypes.family(typeFamilies), aggFunction);
    }

    @Override
    public EnumerableRel implementEnumerable(List<EnumerableRel> inputs) {
        try {
            return new EnumerableAggregate(getCluster(), getCluster().traitSetOf(EnumerableConvention.INSTANCE), //
                    sole(inputs), indicator, this.groupSet, this.groupSets, rewriteAggCalls);
        } catch (InvalidRelException e) {
            throw new IllegalStateException("Can't create EnumerableAggregate!", e);
        }
    }

    @Override
    public OLAPContext getContext() {
        return context;
    }

    @Override
    public ColumnRowType getColumnRowType() {
        return columnRowType;
    }

    @Override
    public boolean hasSubQuery() {
        OLAPRel olapChild = (OLAPRel) getInput();
        return olapChild.hasSubQuery();
    }

    @Override
    public RelTraitSet replaceTraitSet(RelTrait trait) {
        RelTraitSet oldTraitSet = this.traitSet;
        this.traitSet = this.traitSet.replace(trait);
        return oldTraitSet;
    }
}
