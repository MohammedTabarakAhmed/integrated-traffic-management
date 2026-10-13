import { useTranslation } from 'react-i18next';
import { useState } from 'react';
import { Search, MapPin, Building2, Clock, Star, Phone, Navigation2, Map, Filter, FilterX, MapPin as MapPinIcon } from 'lucide-react';
import { Link } from 'react-router-dom';

interface Centre {
  id: number;
  name: string;
  region: string;
  address: string;
  phone: string;
  hours: string;
  services: string[];
  waitTime: string;
  rating: number;
  coordinates: { lat: number; lng: number };
}

interface CentreItemProps {
  centre: Centre;
  t: (key: string, defaultValue?: string) => string;
}

function CentreItem({ centre, t }: CentreItemProps) {
  return (
    <Link to={`/centres/${centre.id}`} className="group p-5 bg-surface rounded-2xl border border-border hover:border-primary/50 hover:shadow-lg transition-all duration-300">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div className="flex items-start sm:items-center gap-4">
          <div className="p-3 rounded-xl bg-primary/10 text-primary">
            <Building2 className="w-6 h-6" />
          </div>
          <div className="flex-1 min-w-0">
            <h3 className="font-semibold text-text group-hover:text-primary transition-colors">{centre.name}</h3>
            <p className="text-sm text-text-secondary flex items-center gap-2">
              <MapPinIcon className="w-4 h-4" />
              {centre.address}
            </p>
            <p className="text-sm text-text-secondary flex items-center gap-2">
              <Phone className="w-4 h-4" />
              {centre.phone}
            </p>
          </div>
        </div>

        <div className="flex flex-col sm:flex-row sm:items-center gap-4">
          <div className="flex items-center gap-4 text-sm text-text-secondary">
            <div className="flex items-center gap-1">
              <Clock className="w-4 h-4" />
              <span>{centre.hours}</span>
            </div>
            <div className="flex items-center gap-1">
              <Clock className="w-4 h-4" />
              <span className="font-medium text-primary">{centre.waitTime}</span>
            </div>
            <div className="flex items-center gap-1">
              <Star className="w-4 h-4 fill-yellow-400 text-yellow-400" />
              <span className="font-medium">{centre.rating}</span>
            </div>
          </div>

          <div className="flex flex-wrap gap-2">
            {centre.services.slice(0, 3).map((s, i) => (
              <span key={i} className="px-2 py-1 text-xs bg-primary/10 text-primary rounded-full">{s}</span>
            ))}
            {centre.services.length > 3 && (
              <span className="px-2 py-1 text-xs bg-gray-100 dark:bg-gray-800 rounded-full text-text-secondary">
                +{centre.services.length - 3} more
              </span>
            )}
          </div>

          <div className="flex items-center gap-2">
            <button className="px-4 py-2 text-sm font-medium text-white bg-primary rounded-xl hover:bg-primary-dark transition-colors">
              <Navigation2 className="w-4 h-4 mr-2" />
              Directions
            </button>
            <button className="px-4 py-2 text-sm font-medium text-primary bg-primary/10 rounded-xl hover:bg-primary/20 transition-colors">
              Book
            </button>
          </div>
        </div>
      </div>
    </Link>
  );
}

export function PublicCentreFinder() {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [serviceFilter, setServiceFilter] = useState('all');
  const [regionFilter, setRegionFilter] = useState('all');
  const [viewMode, setViewMode] = useState<'list' | 'map'>('list');

  const centres: Centre[] = [
    { id: 1, name: 'Dubai Vehicle Testing Centre - Al Quoz', region: 'Dubai', address: 'Al Quoz Industrial Area, Dubai', phone: '800-ITMS', hours: '7:00 AM - 10:00 PM', services: ['Vehicle Inspection', 'Registration', 'Plate Services'], coordinates: { lat: 25.1284, lng: 55.2001 }, waitTime: '15 min', rating: 4.8 },
    { id: 2, name: 'Deira Licensing Centre', region: 'Dubai', address: 'Deira, Dubai', phone: '800-ITMS', hours: '7:30 AM - 8:30 PM', services: ['Licence Issue', 'Licence Renewal', 'Eye Test'], coordinates: { lat: 25.2608, lng: 55.3127 }, waitTime: '20 min', rating: 4.7 },
    { id: 3, name: 'Abu Dhabi Vehicle Testing Centre - Musaffah', region: 'Abu Dhabi', address: 'Musaffah Industrial Area, Abu Dhabi', phone: '800-ITMS', hours: '7:00 AM - 9:00 PM', services: ['Vehicle Inspection', 'Registration', 'Export Certificate'], coordinates: { lat: 24.3667, lng: 54.4500 }, waitTime: '12 min', rating: 4.6 },
    { id: 4, name: 'Al Ain Licensing Centre', region: 'Abu Dhabi', address: 'Al Ain, Abu Dhabi', phone: '800-ITMS', hours: '7:30 AM - 8:30 PM', services: ['Licence Issue', 'Licence Renewal', 'International Permit'], coordinates: { lat: 24.2075, lng: 55.7447 }, waitTime: '25 min', rating: 4.5 },
    { id: 5, name: 'Sharjah Vehicle Testing Centre', region: 'Sharjah', address: 'Industrial Area 17, Sharjah', phone: '800-ITMS', hours: '7:00 AM - 9:00 PM', services: ['Vehicle Inspection', 'Registration', 'Plate Services'], coordinates: { lat: 25.3463, lng: 55.4209 }, waitTime: '18 min', rating: 4.6 },
    { id: 6, name: 'Ajman Licensing Centre', region: 'Ajman', address: 'Al Jurf, Ajman', phone: '800-ITMS', hours: '7:30 AM - 8:30 PM', services: ['Licence Issue', 'Renewal', 'International Permit'], coordinates: { lat: 25.4052, lng: 55.5136 }, waitTime: '15 min', rating: 4.4 },
    { id: 7, name: 'Fujairah Vehicle Testing Centre', region: 'Fujairah', address: 'Sakamkam, Fujairah', phone: '800-ITMS', hours: '7:00 AM - 8:00 PM', services: ['Vehicle Inspection', 'Registration'], coordinates: { lat: 25.1288, lng: 56.3265 }, waitTime: '10 min', rating: 4.5 },
    { id: 8, name: 'Ras Al Khaimah Licensing Centre', region: 'Ras Al Khaimah', address: 'Al Nakheel, RAK', phone: '800-ITMS', hours: '7:30 AM - 8:30 PM', services: ['Licence Services', 'Eye Test'], coordinates: { lat: 25.7895, lng: 55.9432 }, waitTime: '22 min', rating: 4.3 },
    { id: 9, name: 'Umm Al Quwain Vehicle Centre', region: 'Umm Al Quwain', address: 'King Faisal Road, UAQ', phone: '800-ITMS', hours: '7:00 AM - 8:00 PM', services: ['Vehicle Inspection', 'Registration'], coordinates: { lat: 25.5647, lng: 55.5553 }, waitTime: '8 min', rating: 4.4 },
  ];

  const filteredCentres = centres.filter(c =>
    (regionFilter === 'all' || c.region === regionFilter) &&
    (serviceFilter === 'all' || c.services.includes(serviceFilter)) &&
    (c.name.toLowerCase().includes(search.toLowerCase()) || c.address.toLowerCase().includes(search.toLowerCase()) || c.region.toLowerCase().includes(search.toLowerCase()))
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-text mb-2">{t('centreFinder') || 'Service Centre Finder'}</h1>
        <p className="text-text-secondary">{t('centreFinderDesc') || 'Find the nearest ITMS service centre with live queue status and wait times'}</p>
      </div>

      <div className="bg-surface rounded-2xl border border-border p-6">
        <div className="flex flex-col sm:flex-row gap-4 mb-4">
          <div className="flex-1 relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-text-muted" />
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t('searchCentres') || 'Search centres by name, address, or service...'}
              className="w-full pl-10 pr-4 py-3 bg-bg border border-border rounded-xl text-text placeholder-text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
            />
          </div>
          <select value={regionFilter} onChange={(e) => setRegionFilter(e.target.value)} className="px-4 py-3 bg-bg border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByRegion')}>
            {['all', 'Dubai', 'Abu Dhabi', 'Sharjah', 'Ajman', 'Fujairah', 'Ras Al Khaimah', 'Umm Al Quwain'].map(r => <option key={r} value={r}>{r === 'all' ? t('allRegions') : t(r)}</option>)}
          </select>
          <select value={serviceFilter} onChange={(e) => setServiceFilter(e.target.value)} className="px-4 py-3 bg-bg border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary" aria-label={t('filterByService')}>
            {['all', 'Vehicle Inspection', 'Registration', 'Licence Issue', 'Licence Renewal', 'Plate Services', 'Eye Test', 'Export Certificate', 'International Permit'].map(s => <option key={s} value={s}>{s === 'all' ? t('allServices') : t(s)}</option>)}
          </select>
          <div className="flex gap-2">
            <button onClick={() => setViewMode('list')} className={`px-4 py-2 rounded-xl transition-colors ${viewMode === 'list' ? 'bg-primary text-white' : 'bg-bg text-text-secondary hover:bg-primary/5'}`}>
              <MapPin className="w-4 h-4 mr-2" />
              {t('listView') || 'List'}
            </button>
            <button onClick={() => setViewMode('map')} className={`px-4 py-2 rounded-xl transition-colors ${viewMode === 'map' ? 'bg-primary text-white' : 'bg-bg text-text-secondary hover:bg-primary/5'}`}>
              <Map className="w-4 h-4 mr-2" />
              {t('mapView') || 'Map'}
            </button>
          </div>
        </div>

        {search || regionFilter !== 'all' || serviceFilter !== 'all' && (
          <button onClick={() => { setSearch(''); setRegionFilter('all'); setServiceFilter('all'); }} className="px-4 py-2 text-sm font-medium text-primary bg-primary/10 rounded-xl hover:bg-primary/20 transition-colors flex items-center gap-2">
            <FilterX className="w-4 h-4" />
            {t('clearFilters') || 'Clear Filters'}
          </button>
        )}

        <div className="mt-4 flex items-center justify-between">
          <p className="text-sm text-text-secondary">{filteredCentres.length} {t('centresFound') || 'centres found'}</p>
          <div className="flex items-center gap-2">
            <label className="text-sm text-text-secondary">{t('sortBy') || 'Sort by'}</label>
            <select className="px-3 py-2 bg-bg border border-border rounded-xl text-text focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary">
              <option value="distance">{t('distance') || 'Distance'}</option>
              <option value="rating">{t('rating') || 'Rating'}</option>
              <option value="waitTime">{t('waitTime') || 'Wait Time'}</option>
              <option value="name">{t('name') || 'Name'}</option>
            </select>
          </div>
        </div>
      </div>

      {viewMode === 'list' ? (
        <div className="space-y-4">
          {filteredCentres.map((centre) => (
            <CentreItem key={centre.id} centre={centre} t={t} />
          ))}
        </div>
      ) : (
        <div className="bg-surface rounded-2xl border border-border overflow-hidden">
          <div className="aspect-video bg-gray-100 dark:bg-gray-800 flex items-center justify-center">
            <div className="text-center p-8">
              <Map className="w-16 h-16 text-text-muted mx-auto mb-4" />
              <h3 className="text-lg font-semibold text-text mb-2">{t('mapViewComingSoon') || 'Interactive Map Coming Soon'}</h3>
              <p className="text-text-secondary max-w-md mx-auto">{t('mapViewDesc') || 'Interactive map with live traffic, directions, and real-time queue status will be available soon.'}</p>
            </div>
          </div>
        </div>
      )}

      {filteredCentres.length === 0 && (
        <div className="text-center py-12 bg-surface rounded-2xl border border-border">
          <MapPin className="w-12 h-12 text-text-muted mx-auto mb-4" />
          <h3 className="text-lg font-semibold text-text mb-2">{t('noCentresFound') || 'No Centres Found'}</h3>
          <p className="text-text-muted mb-4">{t('noCentresDesc') || 'Try adjusting your search or filters to find matching centres.'}</p>
          <button onClick={() => { setSearch(''); setRegionFilter('all'); setServiceFilter('all'); }} className="px-4 py-2 text-sm font-medium text-primary bg-primary/10 rounded-xl hover:bg-primary/20 transition-colors">
            {t('clearFilters') || 'Clear Filters'}
          </button>
        </div>
      )}
    </div>
  );
}