package net.imglib2.cache.ref;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import net.imglib2.cache.CacheLoader;
import net.imglib2.cache.LoaderCache;

public class SoftRefLoaderCache< K, V > implements LoaderCache< K, V >
{
	final ConcurrentHashMap< K, Entry > map = new ConcurrentHashMap<>();

	final ReferenceQueue< V > queue = new ReferenceQueue<>();

	final class CacheSoftReference extends SoftReference< V >
	{
		private final Entry entry;

		public CacheSoftReference()
		{
			super( null );
			this.entry = null;
		}

		public CacheSoftReference( final V referent, final Entry entry )
		{
			super( referent, queue );
			this.entry = entry;
		}

		public void clean()
		{
			map.remove( entry.key, entry );
		}
	}

	final class Entry
	{
		final K key;

		private SoftReference< V > ref;

		boolean loaded;

		public Entry( final K key )
		{
			this.key = key;
			this.ref = new CacheSoftReference();
			this.loaded = false;
		}

		public V getValue()
		{
			return ref.get();
		}

		public void setValue( final V value )
		{
			this.loaded = true;
			this.ref = new CacheSoftReference( value, this );
		}
	}

	@Override
	public V getIfPresent( final K key )
	{
		cleanUp();
		final Entry entry = map.get( key );
		return entry == null ? null : entry.getValue();
	}

	@Override
	public V get( final K key, final CacheLoader< ? super K, ? extends V > loader ) throws ExecutionException
	{
		cleanUp();
		final Entry entry = map.computeIfAbsent( key, ( k ) -> new Entry( k ) );
		V value = entry.getValue();
		if ( value == null )
		{
			synchronized ( entry )
			{
				if ( entry.loaded )
				{
					value = entry.getValue();
					if ( value == null )
					{
						/*
						 * The entry was already loaded, but its value has been
						 * garbage collected. We need to create a new entry
						 */
						map.remove( key, entry );
						value = get( key, loader );
					}
				}
				else
				{
					try
					{
						value = loader.get( key );
						entry.setValue( value );
					}
					catch ( final InterruptedException e )
					{
						Thread.currentThread().interrupt();
						throw new ExecutionException( e );
					}
					catch ( final Exception e )
					{
						throw new ExecutionException( e );
					}
				}
			}
		}
		return value;
	}

	@Override
	public void invalidateAll()
	{
		map.clear();
	}

	/**
	 * Remove entries from the cache whose references have been
	 * garbage-collected.
	 */
	public void cleanUp()
	{
		while ( true )
		{
			@SuppressWarnings( "unchecked" )
			final CacheSoftReference poll = ( CacheSoftReference ) queue.poll();
			if ( poll == null )
				break;
			poll.clean();
		}
	}
}
