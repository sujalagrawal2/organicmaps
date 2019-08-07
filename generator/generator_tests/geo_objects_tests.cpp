#include "testing/testing.hpp"

#include "platform/platform_tests_support/scoped_file.hpp"

#include "generator/feature_builder.hpp"
#include "generator/feature_generator.hpp"
#include "generator/generator_tests/common.hpp"
#include "generator/geo_objects/geo_object_info_getter.hpp"
#include "generator/geo_objects/geo_objects.hpp"
#include "generator/geo_objects/geo_objects_filter.hpp"

#include "indexer/classificator_loader.hpp"

#include <iostream>

using namespace generator_tests;
using namespace platform::tests_support;
using namespace generator;
using namespace generator::geo_objects;
using namespace feature;
using namespace base;

bool CheckWeGotExpectedIdsByPoint(m2::PointD const & point,
                                  std::vector<base::GeoObjectId> reference,
                                  indexer::GeoObjectsIndex<IndexReader> const & index)
{
  std::vector<base::GeoObjectId> test = GeoObjectInfoGetter::SearchObjectsInIndex(index, point);
  std::sort(test.begin(), test.end());
  std::sort(reference.begin(), reference.end());
  return test == reference;
}

std::vector<base::GeoObjectId> CollectFeatures(
    std::vector<OsmElementData> const & osmElements, ScopedFile const & geoObjectsFeatures,
    std::function<bool(FeatureBuilder const &)> && accepter)
{
  std::vector<base::GeoObjectId> expectedIds;
  {
    FeaturesCollector collector(geoObjectsFeatures.GetFullPath());
    for (OsmElementData const & elementData : osmElements)
    {
      FeatureBuilder fb = FeatureBuilderFromOmsElementData(elementData);
      if (accepter(fb))
        expectedIds.emplace_back(fb.GetMostGenericOsmId());

      TEST(fb.PreSerialize(), ());
      collector.Collect(fb);
    }
  }

  return expectedIds;
}

GeoObjectsGenerator TearUp(std::vector<OsmElementData> const & osmElements,
                           ScopedFile const & geoObjectsFeatures,
                           ScopedFile const & idsWithoutAddresses,
                           ScopedFile const & geoObjectsKeyValue)
{
  // Absolutely random region.
  std::shared_ptr<JsonValue> value = std::make_shared<JsonValue>(LoadFromString(
      R"({
               "type": "Feature",
               "geometry": {
                 "type": "Point",
                 "coordinates": [
                   -77.263927,
                   26.6210869
                 ]
               },
               "properties": {
                 "locales": {
                   "default": {
                     "address": {
                       "country": "Bahamas",
                       "region": "Central Abaco",
                       "locality": "Leisure Lee"
                     },
                     "name": "Leisure Lee"
                   },
                   "en": {
                     "address": {
                       "country": "Bahamas",
                       "region": "Central Abaco",
                       "locality": "Leisure Lee"
                     },
                     "name": "Leisure Lee"
                   },
                   "ru": {
                     "address": {
                       "country": "Багамы"
                     }
                   }
                 },
                 "rank": 4,
                 "dref": "C00000000039A088",
                 "code": "BS"
               }
             })"));

  auto regionGetter = [value](auto && point) { return KeyValue{1, value}; };

  return GeoObjectsGenerator{regionGetter,
                             geoObjectsFeatures.GetFullPath(),
                             idsWithoutAddresses.GetFullPath(),
                             geoObjectsKeyValue.GetFullPath(),
                             false /* verbose */,
                             1 /* threadsCount */};
}

void TestFindReverse(std::vector<OsmElementData> const & osmElements,
                     std::vector<m2::PointD> const & where)
{
  classificator::Load();
  ScopedFile const geoObjectsFeatures{"geo_objects_features.mwm", ScopedFile::Mode::DoNotCreate};
  ScopedFile const idsWithoutAddresses{"ids_without_addresses.txt", ScopedFile::Mode::DoNotCreate};
  ScopedFile const geoObjectsKeyValue{"geo_objects.jsonl", ScopedFile::Mode::DoNotCreate};

  auto const & expectedIds = CollectFeatures(
      osmElements, geoObjectsFeatures, [](FeatureBuilder const & fb) { return fb.IsPoint(); });

  GeoObjectsGenerator geoObjectsGenerator =
      TearUp(osmElements, geoObjectsFeatures, idsWithoutAddresses, geoObjectsKeyValue);

  TEST(geoObjectsGenerator.GenerateGeoObjects(), ("Generate Geo Objects failed"));

  auto const geoObjectsIndex = MakeTempGeoObjectsIndex(geoObjectsFeatures.GetFullPath());

  TEST(geoObjectsIndex.has_value(), ("Temporary index build failed"));

  for (auto const & point : where)
  {
    TEST(CheckWeGotExpectedIdsByPoint(point, expectedIds, *geoObjectsIndex), ());
  }
}

UNIT_TEST(GenerateGeoObjects_AddNullBuildingGeometryForPointsWithAddressesInside)
{
  std::vector<OsmElementData> const osmElements{
      {1,
       {{"addr:housenumber", "39 с79"},
        {"addr:street", "Ленинградский проспект"},
        {"building", "yes"}},
       {{1.5, 1.5}},
       {}},
      {2,
       {{"building", "commercial"}, {"type", "multipolygon"}, {"name", "superbuilding"}},
       {{1, 1}, {4, 4}},
       {}},
      {3,
       {{"addr:housenumber", "39 с80"},
        {"addr:street", "Ленинградский проспект"}},
       {{1.6, 1.6}},
       {}}
  };

  TestFindReverse(osmElements, {{1.5, 1.5}, {2, 2}, {4, 4}});
}

UNIT_TEST(GenerateGeoObjects_AddNullBuildingGeometryForPointsWithAddressesInside2)
{
  std::vector<OsmElementData> const osmElements{
      {1,
       {{"addr:housenumber", "39 с80"},
        {"addr:street", "Ленинградский проспект"}},
       {{1.6, 1.6}},
       {}
      },
      {2,
       {{"addr:housenumber", "39 с79"},
        {"addr:street", "Ленинградский проспект"},
        {"building", "yes"}},
       {{1.5, 1.5}},
       {}},
      {3,
       {{"building", "commercial"}, {"type", "multipolygon"}, {"name", "superbuilding"}},
       {{1, 1}, {4, 4}},
       {}},

  };
  TestFindReverse(osmElements, {{1.5, 1.5}, {2, 2}, {4, 4}});
}

UNIT_TEST(GenerateGeoObjects_AddNullBuildingPointToPoint)
{
  std::vector<OsmElementData> const osmElements{
      {1,
       {{"addr:housenumber", "39 с79"},
        {"addr:street", "Ленинградский проспект"},
        {"building", "yes"}},
       {{1.5, 1.5}},
       {}},
      {3,
       {{"building", "commercial"}, {"type", "multipolygon"}, {"name", "superbuilding"}},
       {{1.5, 1.5}},
       {}},
  };
  TestFindReverse(osmElements, {});
}

void TestPoiHasAddress(std::vector<OsmElementData> const & osmElements)
{
  classificator::Load();
  ScopedFile const geoObjectsFeatures{"geo_objects_features.mwm", ScopedFile::Mode::DoNotCreate};
  ScopedFile const idsWithoutAddresses{"ids_without_addresses.txt", ScopedFile::Mode::DoNotCreate};
  ScopedFile const geoObjectsKeyValue{"geo_objects.jsonl", ScopedFile::Mode::DoNotCreate};

  auto const & expectedIds =
      CollectFeatures(osmElements, geoObjectsFeatures,
                      [](FeatureBuilder const & fb) { return GeoObjectsFilter::IsPoi(fb); });

  GeoObjectsGenerator geoObjectsGenerator =
      TearUp(osmElements, geoObjectsFeatures, idsWithoutAddresses, geoObjectsKeyValue);

  TEST(geoObjectsGenerator.GenerateGeoObjects(), ("Generate Geo Objects failed"));
  for (GeoObjectId id : expectedIds)
  {
    std::shared_ptr<JsonValue> value =
        geoObjectsGenerator.GetKeyValueStorage().Find(id.GetEncodedId());
    TEST(value, ("Id", id, "is not stored in key value"));
    TEST(JsonHasBuilding(*value), ("No address for", id));
  }
}

UNIT_TEST(GenerateGeoObjects_CheckPoiEnricedWithAddress)
{
  std::vector<OsmElementData> const osmElements{
      {1, {{"addr:housenumber", "111"}, {"addr:street", "Healing street"}}, {{1.6, 1.6}}, {}},
      {2,
       {{"building", "commercial"}, {"type", "multipolygon"}, {"name", "superbuilding"}},
       {{1, 1}, {4, 4}},
       {}},
      {3,
       {{"shop", "supermarket"}, {"population", "1"}, {"name", "ForgetMeNot"}},
       {{1.5, 1.5}},
       {}},
  };

  TestPoiHasAddress(osmElements);
}
