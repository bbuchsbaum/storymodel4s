import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import { sidebar } from './src/lib/navigation.mjs';

export default defineConfig({
  integrations: [
    starlight({
      title: 'storymodel4s',
      description: 'Evidence-first models of stories and their recall.',
      favicon: '/favicon.svg',
      logo: {
        src: './src/assets/mark.svg',
        replacesTitle: false,
      },
      customCss: ['./src/styles/custom.css'],
      social: [
        {
          icon: 'github',
          label: 'Source repository',
          href: 'https://github.com/bbuchsbaum/storymodel4s',
        },
      ],
      sidebar,
    }),
  ],
});
