package gov.usgs.cida.gdp.coreprocessing.analysis.grid;

import gov.usgs.cida.gdp.coreprocessing.analysis.grid.FeatureCoverageWeightedGridStatisticsWriter.Statistic;
import gov.usgs.cida.gdp.coreprocessing.analysis.statistics.WeightedStatistics1D;
import gov.usgs.cida.gdp.coreprocessing.analysis.grid.GridCellCoverageFactory.GridCellCoverageByIndex;
import gov.usgs.cida.gdp.coreprocessing.analysis.grid.GridCellCoverageFactory.GridCellIndexCoverage;

import com.google.common.base.Preconditions;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.feature.FeatureCollection;
import org.geotools.feature.SchemaException;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import ucar.ma2.InvalidRangeException;
import ucar.ma2.Range;
import ucar.nc2.dataset.CoordinateAxis1DTime;
import ucar.nc2.dt.GridCoordSystem;
import ucar.nc2.dt.GridDataset;
import ucar.nc2.dt.GridDatatype;

public class FeatureCoverageWeightedGridStatistics {

    public static void execute(
            FeatureCollection<SimpleFeatureType, SimpleFeature> featureCollection,
            String attributeName,
            GridDataset gridDataset,
            String variableName,
            Range timeRange,
            List<Statistic> statisticList,
            BufferedWriter writer,
            boolean groupByStatistic,
            String delimiter)
            throws IOException, InvalidRangeException, FactoryException, TransformException, SchemaException
    {

        GridDatatype gdt = gridDataset.findGridDatatype(variableName);
        Preconditions.checkNotNull(gdt, "Variable named %s not found in gridDataset", variableName);

        GridType gt = GridType.findGridType(gdt.getCoordinateSystem());
        if( !(gt == GridType.YX || gt == GridType.TYX) ) {
            throw new IllegalStateException("Currently require y-x or t-y-x grid for this operation");
        }

        try {
            Range[] ranges = GridUtility.getRangesFromBoundingBox(
                    featureCollection.getBounds(), gdt.getCoordinateSystem());
            gdt = gdt.makeSubset(null, null, timeRange, null, ranges[1], ranges[0]);
        } catch (InvalidRangeException ex) {
            Logger.getLogger(FeatureCoverageWeightedGridStatistics.class.getName()).log(Level.SEVERE, null, ex);
            throw ex;  // rethrow requested by IS
        }

        GridCoordSystem gcs = gdt.getCoordinateSystem();

        GridCellCoverageByIndex coverageByIndex =
				GridCellCoverageFactory.generateFeatureAttributeCoverageByIndex(
                    featureCollection,
                    attributeName,
                    gdt.getCoordinateSystem());

        String variableUnits = gdt.getVariable().getUnitsString();

        List<Object> attributeList = coverageByIndex.getAttributeValueList();

        FeatureCoverageWeightedGridStatisticsWriter writerX =
                new FeatureCoverageWeightedGridStatisticsWriter(
                    attributeList,
                    variableName,
                    variableUnits,
                    statisticList,
                    groupByStatistic,
                    delimiter,
                    writer);

        WeightedGridStatisticsVisitor v = null;
        switch (gt) {
            case YX:
                v = new WeightedGridStatisticsVisitor_YX(coverageByIndex, writerX);
            break;
            case TYX:
                v = new WeightedGridStatisticsVisitor_TYX(coverageByIndex, writerX, gcs.getTimeAxis1D(), timeRange);
            break;
            default:
                throw new IllegalStateException("Currently require y-x or t-y-x grid for this operation");
        }

        GridCellTraverser gct = new GridCellTraverser(gdt);

        gct.traverse(v);
    }

    public static abstract class FeatureCoverageGridCellVisitor extends GridCellVisitor {

        final protected GridCellCoverageByIndex coverageByIndex;

        public FeatureCoverageGridCellVisitor(GridCellCoverageByIndex coverageByIndex) {
            this.coverageByIndex = coverageByIndex;
        }

        @Override
        public void processGridCell(int xCellIndex, int yCellIndex, double value) {
            double coverageTotal = 0;
			List<GridCellIndexCoverage> list = coverageByIndex.getCoverageList(xCellIndex, yCellIndex);
			if (list != null) {
				for (GridCellIndexCoverage c : list) {
					if (c.coverage > 0.0) {
						processPerAttributeGridCellCoverage(value, c.coverage, c.attribute);
					}
					coverageTotal += c.coverage;
				}
			}
            if (coverageTotal > 0.0) {
                processAllAttributeGridCellCoverage(value, coverageTotal);
            }
        }

        public abstract void processPerAttributeGridCellCoverage(double value, double coverage, Object attribute);

        public abstract void processAllAttributeGridCellCoverage(double value, double coverage);

    }


    protected static abstract class WeightedGridStatisticsVisitor extends FeatureCoverageGridCellVisitor {

        protected Map<Object, WeightedStatistics1D> perAttributeStatistics;
        protected WeightedStatistics1D allAttributeStatistics;

        public WeightedGridStatisticsVisitor(GridCellCoverageByIndex coverageByIndex) {
            super(coverageByIndex);
        }

        protected Map<Object, WeightedStatistics1D> createPerAttributeStatisticsMap() {
            Map map = new LinkedHashMap<Object, WeightedStatistics1D>();
            for (Object attributeValue : coverageByIndex.getAttributeValueList()) {
                map.put(attributeValue, new WeightedStatistics1D());
            }
            return map;
        }

        @Override
        public void yxStart() {
            perAttributeStatistics = createPerAttributeStatisticsMap();
            allAttributeStatistics = new WeightedStatistics1D();
        }

        @Override
        public void processPerAttributeGridCellCoverage(double value, double coverage, Object attribute) {
            perAttributeStatistics.get(attribute).accumulate(value, coverage);
        }

        @Override
        public void processAllAttributeGridCellCoverage(double value, double coverage) {
            allAttributeStatistics.accumulate(value, coverage);
        }
    }

    protected static class WeightedGridStatisticsVisitor_YX extends WeightedGridStatisticsVisitor {

        FeatureCoverageWeightedGridStatisticsWriter writer;

        WeightedGridStatisticsVisitor_YX(
                GridCellCoverageByIndex coverageByIndex,
                FeatureCoverageWeightedGridStatisticsWriter writer) {
            super(coverageByIndex);
            this.writer = writer;
        }

        @Override
        public void traverseStart(GridCoordSystem gridCoordSystem) {
            try {
                writer.writerHeader(null);
            } catch (IOException ex) {
                // TODO
            }
        }

        @Override
        public void traverseEnd() {
            try {
                writer.writeRow(
                        null,
                        perAttributeStatistics.values(),
                        allAttributeStatistics);
            } catch (IOException ex) {
                // TODO
            }
        }

    }

    protected static class WeightedGridStatisticsVisitor_TYX extends WeightedGridStatisticsVisitor {

        protected Map<Object, WeightedStatistics1D> allTimestepPerAttributeStatistics;
        protected WeightedStatistics1D allTimestepAllAttributeStatistics;

        protected FeatureCoverageWeightedGridStatisticsWriter writer;
        
        protected CoordinateAxis1DTime tAxis;
        protected int tIndexOffset;

        public WeightedGridStatisticsVisitor_TYX(
                GridCellCoverageByIndex coverageByIndex,
                FeatureCoverageWeightedGridStatisticsWriter writer,
                CoordinateAxis1DTime tAxis,
                Range tRange) {
            super(coverageByIndex);
            this.writer = writer;
            this.tAxis = tAxis;
            this.tIndexOffset = tRange.first();
        }

        @Override
        public void traverseStart(GridCoordSystem gridCoordSystem) {
            
            try {
                writer.writerHeader(FeatureCoverageWeightedGridStatisticsWriter.TIMESTEPS_LABEL);
            } catch (IOException ex) {
                // TODO
            }

            allTimestepPerAttributeStatistics =
                    createPerAttributeStatisticsMap();
            allTimestepAllAttributeStatistics = new WeightedStatistics1D();
        }

        @Override
        public void processPerAttributeGridCellCoverage(double value, double coverage, Object attribute) {
            super.processPerAttributeGridCellCoverage(value, coverage, attribute);
            allTimestepPerAttributeStatistics.get(attribute).accumulate(value, coverage);
        }

        @Override
        public void processAllAttributeGridCellCoverage(double value, double coverage) {
            super.processAllAttributeGridCellCoverage(value, coverage);
            allTimestepAllAttributeStatistics.accumulate(value, coverage);
        }

        @Override
        public void tEnd(int tIndex) {
            try {
                writer.writeRow(
                        tAxis.getTimeDate(tIndexOffset + tIndex).toGMTString(),
                        perAttributeStatistics.values(),
                        allAttributeStatistics);
            } catch (IOException e) {
                // TODO
            }
        }

        @Override
        public void traverseEnd() {
            try {
                writer.writeRow(
                        FeatureCoverageWeightedGridStatisticsWriter.ALL_TIMESTEPS_LABEL,
                        allTimestepPerAttributeStatistics.values(),
                        allTimestepAllAttributeStatistics);
            } catch (IOException ex) {
                // TODO
            }
        }
    }

}
