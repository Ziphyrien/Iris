package com.volmit.iris.gen;

import java.io.IOException;
import java.util.List;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;

import com.volmit.iris.gen.atomics.AtomicSliver;
import com.volmit.iris.gen.atomics.AtomicSliverMap;
import com.volmit.iris.gen.atomics.AtomicWorldData;
import com.volmit.iris.gen.atomics.MasterLock;
import com.volmit.iris.gen.layer.GenLayerText;
import com.volmit.iris.gen.layer.GenLayerUpdate;
import com.volmit.iris.object.IrisBiome;
import com.volmit.iris.object.IrisBiomeMutation;
import com.volmit.iris.object.IrisObjectPlacement;
import com.volmit.iris.object.IrisRegion;
import com.volmit.iris.object.IrisStructurePlacement;
import com.volmit.iris.object.IrisTextPlacement;
import com.volmit.iris.util.BiomeMap;
import com.volmit.iris.util.CaveResult;
import com.volmit.iris.util.ChunkPosition;
import com.volmit.iris.util.HeightMap;
import com.volmit.iris.util.IObjectPlacer;
import com.volmit.iris.util.IrisLock;
import com.volmit.iris.util.IrisStructureResult;
import com.volmit.iris.util.J;
import com.volmit.iris.util.KList;
import com.volmit.iris.util.KMap;
import com.volmit.iris.util.PrecisionStopwatch;
import com.volmit.iris.util.RNG;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public abstract class ParallaxChunkGenerator extends TerrainChunkGenerator implements IObjectPlacer
{
	private short cacheID = 0;
	private KMap<ChunkPosition, AtomicSliver> sliverCache;
	private AtomicWorldData parallaxMap;
	private MasterLock masterLock;
	private IrisLock flock = new IrisLock("ParallaxLock");
	private IrisLock lock = new IrisLock("ParallaxLock");
	private GenLayerUpdate glUpdate;
	private GenLayerText glText;
	private int sliverBuffer;

	public ParallaxChunkGenerator(String dimensionName, int threads)
	{
		super(dimensionName, threads);
		setSliverCache(new KMap<>());
		setSliverBuffer(sliverBuffer);
		setMasterLock(new MasterLock());
	}

	public void onInit(World world, RNG rng)
	{
		super.onInit(world, rng);
		setParallaxMap(new AtomicWorldData(world));
		setGlText(new GenLayerText(this, rng.nextParallelRNG(32485)));
		setGlUpdate(null);
		J.a(() -> getDimension().getParallaxSize(this));
	}

	protected void onClose()
	{
		super.onClose();

		try
		{
			getParallaxMap().unloadAll(true);
		}

		catch(IOException e)
		{
			e.printStackTrace();
		}
	}

	@Override
	public int getHighest(int x, int z)
	{
		return getHighest(x, z, false);
	}

	@Override
	public void onHotload()
	{
		getData().preferFolder(getDimension().getLoadFile().getParentFile().getParentFile().getName());
		super.onHotload();
		setCacheID(RNG.r.simax());
	}

	@Override
	public int getHighest(int x, int z, boolean ignoreFluid)
	{
		return getCarvedHeight(x, z, ignoreFluid);
	}

	@Override
	public void set(int x, int y, int z, BlockData d)
	{
		getParallaxSliver(x, z).set(y, d);
	}

	@Override
	public BlockData get(int x, int y, int z)
	{
		BlockData b = sampleSliver(x, z).getBlock().get(y);
		return b == null ? AIR : b;
	}

	@Override
	public boolean isSolid(int x, int y, int z)
	{
		return get(x, y, z).getMaterial().isSolid();
	}

	public AtomicSliver getParallaxSliver(int wx, int wz)
	{
		getMasterLock().lock("gpc");
		getMasterLock().lock((wx >> 4) + "." + (wz >> 4));
		AtomicSliverMap map = getParallaxChunk(wx >> 4, wz >> 4);
		getMasterLock().unlock("gpc");
		AtomicSliver sliver = map.getSliver(wx & 15, wz & 15);
		getMasterLock().unlock((wx >> 4) + "." + (wz >> 4));

		return sliver;
	}

	public boolean isParallaxGenerated(int x, int z)
	{
		return getParallaxChunk(x, z).isParallaxGenerated();
	}

	public boolean isWorldGenerated(int x, int z)
	{
		return getParallaxChunk(x, z).isWorldGenerated();
	}

	public AtomicSliverMap getParallaxChunk(int x, int z)
	{
		try
		{
			return getParallaxMap().loadChunk(x, z);
		}

		catch(IOException e)
		{
			fail(e);
		}

		return new AtomicSliverMap();
	}

	@Override
	public List<BlockPopulator> getDefaultPopulators(World world)
	{
		List<BlockPopulator> g = super.getDefaultPopulators(world);

		if(getGlUpdate() == null)
		{
			setGlUpdate(new GenLayerUpdate(this, world));
		}

		g.add(getGlUpdate());
		return g;
	}

	@Override
	protected void onPostGenerate(RNG random, int x, int z, ChunkData data, BiomeGrid grid, HeightMap height, BiomeMap biomeMap, AtomicSliverMap map)
	{
		if(getSliverCache().size() > 20000)
		{
			getSliverCache().clear();
		}

		super.onPostGenerate(random, x, z, data, grid, height, biomeMap, map);
		PrecisionStopwatch p = PrecisionStopwatch.start();

		if(getDimension().isPlaceObjects())
		{
			onGenerateParallax(random, x, z);
			getParallaxChunk(x, z).inject(data);
		}

		getParallaxChunk(x, z).injectUpdates(map);
		setSliverBuffer(getSliverCache().size());
		getParallaxChunk(x, z).setWorldGenerated(true);
		getMasterLock().clear();
		p.end();
		getMetrics().getParallax().put(p.getMilliseconds());
		super.onPostParallaxPostGenerate(random, x, z, data, grid, height, biomeMap, map);
		getParallaxMap().clean(getTicks());
		getData().getObjectLoader().clean();
	}

	public IrisStructureResult getStructure(int x, int y, int z)
	{
		return getParallaxChunk(x >> 4, z >> 4).getStructure(this, y);
	}

	protected void onGenerateParallax(RNG randomx, int x, int z)
	{
		String key = "par." + x + "." + z;
		ChunkPosition rad = getDimension().getParallaxSize(this);

		for(int ii = x - (rad.getX() / 2); ii <= x + (rad.getX() / 2); ii++)
		{
			int i = ii;

			for(int jj = z - (rad.getZ() / 2); jj <= z + (rad.getZ() / 2); jj++)
			{
				int j = jj;

				RNG random = getMasterRandom().nextParallelRNG(i).nextParallelRNG(j);

				if(isParallaxGenerated(ii, jj))
				{
					continue;
				}

				if(isWorldGenerated(ii, jj))
				{
					continue;
				}

				getAccelerant().queue(key, () ->
				{
					IrisBiome b = sampleTrueBiome((i * 16) + 7, (j * 16) + 7);
					IrisRegion r = sampleRegion((i * 16) + 7, (j * 16) + 7);
					RNG ro = getMasterRandom().nextParallelRNG(496888 + i + j);
					int g = 1;
					g = placeMutations(ro, random, i, j, g);
					g = placeText(random, r, b, i, j, g);
					g = placeObjects(random, r, b, i, j, g);
					g = placeCaveObjects(ro, random, i, j, g);
					g = placeStructures(randomx, r, b, i, j, g);
				});

				getParallaxChunk(ii, jj).setParallaxGenerated(true);
			}
		}

		getAccelerant().waitFor(key);
	}

	private int placeMutations(RNG ro, RNG random, int i, int j, int g)
	{
		searching: for(IrisBiomeMutation k : getDimension().getMutations())
		{
			for(int l = 0; l < k.getChecks(); l++)
			{
				IrisBiome sa = sampleTrueBiome(((i * 16) + ro.nextInt(16)) + ro.i(-k.getRadius(), k.getRadius()), ((j * 16) + ro.nextInt(16)) + ro.i(-k.getRadius(), k.getRadius()));
				IrisBiome sb = sampleTrueBiome(((i * 16) + ro.nextInt(16)) + ro.i(-k.getRadius(), k.getRadius()), ((j * 16) + ro.nextInt(16)) + ro.i(-k.getRadius(), k.getRadius()));

				if(sa.getLoadKey().equals(sb.getLoadKey()))
				{
					continue;
				}

				if(k.getRealSideA(this).contains(sa.getLoadKey()) && k.getRealSideB(this).contains(sb.getLoadKey()))
				{
					for(IrisObjectPlacement m : k.getObjects())
					{
						placeObject(m, i, j, random.nextParallelRNG((34 * ((i * 30) + (j * 30) + g++) * i * j) + i - j + 1569962));
					}

					continue searching;
				}
			}
		}

		return g;
	}

	private int placeText(RNG random, IrisRegion r, IrisBiome b, int i, int j, int g)
	{
		for(IrisTextPlacement k : getDimension().getText())
		{
			k.place(this, random.nextParallelRNG(g++ + -7228 + (34 * ((i * 30) + (j * 30)) * i * j) + i - j + 1569962), i, j);
		}

		for(IrisTextPlacement k : r.getText())
		{
			k.place(this, random.nextParallelRNG(g++ + -4228 + -7228 + (34 * ((i * 30) + (j * 30)) * i * j) + i - j + 1569962), i, j);
		}

		for(IrisTextPlacement k : b.getText())
		{
			k.place(this, random.nextParallelRNG(g++ + -22228 + -4228 + -7228 + (34 * ((i * 30) + (j * 30)) * i * j) + i - j + 1569962), i, j);
		}

		return g;
	}

	private int placeObjects(RNG random, IrisRegion r, IrisBiome b, int i, int j, int g)
	{
		for(IrisObjectPlacement k : b.getObjects())
		{
			placeObject(k, i, j, random.nextParallelRNG((34 * ((i * 30) + (j * 30) + g++) * i * j) + i - j + 3569222));
		}

		for(IrisObjectPlacement k : r.getObjects())
		{
			placeObject(k, i, j, random.nextParallelRNG((34 * ((i * 30) + (j * 30) + g++) * i * j) + i - j + 3569222));
		}

		return g;
	}

	private int placeCaveObjects(RNG ro, RNG random, int i, int j, int g)
	{
		if(!getDimension().isCaves())
		{
			return g;
		}

		int bx = (i * 16) + ro.nextInt(16);
		int bz = (j * 16) + ro.nextInt(16);

		IrisBiome biome = sampleCaveBiome(bx, bz);

		if(biome == null)
		{
			return g;
		}

		if(biome.getObjects().isEmpty())
		{
			return g;
		}

		for(IrisObjectPlacement k : biome.getObjects())
		{
			int gg = g++;
			placeCaveObject(k, i, j, random.nextParallelRNG((34 * ((i * 30) + (j * 30) + gg) * i * j) + i - j + 1869322));
		}

		return g;
	}

	private int placeStructures(RNG random, IrisRegion r, IrisBiome b, int i, int j, int g)
	{
		for(IrisStructurePlacement k : r.getStructures())
		{
			k.place(this, random.nextParallelRNG(2228 * 2 * g++), i, j);
		}

		for(IrisStructurePlacement k : b.getStructures())
		{
			k.place(this, random.nextParallelRNG(-22228 * 4 * g++), i, j);
		}

		return g;
	}

	public void placeObject(IrisObjectPlacement o, int x, int z, RNG rng)
	{
		for(int i = 0; i < o.getTriesForChunk(rng); i++)
		{
			rng = rng.nextParallelRNG((i * 3 + 8) - 23040);
			o.getSchematic(this, rng).place((x * 16) + rng.nextInt(16), (z * 16) + rng.nextInt(16), this, o, rng);
		}
	}

	public void placeCaveObject(IrisObjectPlacement o, int x, int z, RNG rng)
	{
		for(int i = 0; i < o.getTriesForChunk(rng); i++)
		{
			rng = rng.nextParallelRNG((i * 3 + 8) - 23040);
			int xx = (x * 16) + rng.nextInt(16);
			int zz = (z * 16) + rng.nextInt(16);
			KList<CaveResult> res = getCaves(xx, zz);

			if(res.isEmpty())
			{
				continue;
			}

			o.getSchematic(this, rng).place(xx, res.get(rng.nextParallelRNG(29345 * (i + 234)).nextInt(res.size())).getFloor() + 2, zz, this, o, rng);
		}
	}

	public AtomicSliver sampleSliver(int x, int z)
	{
		ChunkPosition key = new ChunkPosition(x, z);

		if(getSliverCache().containsKey(key))
		{
			return getSliverCache().get(key);
		}

		AtomicSliver s = new AtomicSliver(x & 15, z & 15);
		onGenerateColumn(x >> 4, z >> 4, x, z, x & 15, z & 15, s, null, true);
		getSliverCache().put(key, s);

		return s;
	}

	@Override
	public boolean isDebugSmartBore()
	{
		return getDimension().isDebugSmartBore();
	}

	@Override
	public boolean isPreventingDecay()
	{
		return getDimension().isPreventLeafDecay();
	}
}
